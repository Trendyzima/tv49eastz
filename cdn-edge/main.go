package main

import (
	"bufio"
	"context"
	"container/list"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type Config struct {
	Listen         string
	Origin         *url.URL
	MaxCacheBytes  int64
	MaxObjectBytes int64
	SegmentTTL     time.Duration
	OriginTimeout  time.Duration
}

type cacheEntry struct {
	key       string
	body      []byte
	contentType string
	expiresAt time.Time
}

type lruCache struct {
	mu       sync.Mutex
	maxBytes int64
	bytes    int64
	items    map[string]*list.Element
	order    *list.List
}

func newLRU(maxBytes int64) *lruCache {
	return &lruCache{maxBytes: maxBytes, items: make(map[string]*list.Element), order: list.New()}
}

func (c *lruCache) get(key string, now time.Time) (cacheEntry, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.items[key]
	if !ok {
		return cacheEntry{}, false
	}
	entry := e.Value.(cacheEntry)
	if !entry.expiresAt.After(now) {
		c.removeLocked(e)
		return cacheEntry{}, false
	}
	c.order.MoveToFront(e)
	return entry, true
}

func (c *lruCache) put(entry cacheEntry) {
	if int64(len(entry.body)) > c.maxBytes {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if old, ok := c.items[entry.key]; ok {
		c.bytes -= int64(len(old.Value.(cacheEntry).body))
		c.removeLocked(old)
	}
	for c.bytes+int64(len(entry.body)) > c.maxBytes && c.order.Len() > 0 {
		c.removeLocked(c.order.Back())
	}
	e := c.order.PushFront(entry)
	c.items[entry.key] = e
	c.bytes += int64(len(entry.body))
}

func (c *lruCache) removeLocked(e *list.Element) {
	entry := e.Value.(cacheEntry)
	delete(c.items, entry.key)
	c.bytes -= int64(len(entry.body))
	c.order.Remove(e)
}

type Edge struct {
	cfg       Config
	client    *http.Client
	cache     *lruCache
	inflight  sync.Map
	hits      atomic.Uint64
	misses    atomic.Uint64
	originErr atomic.Uint64
}

func main() {
	cfg, err := loadConfig()
	if err != nil {
		log.Fatal(err)
	}
	e := &Edge{
		cfg:   cfg,
		cache: newLRU(cfg.MaxCacheBytes),
		client: &http.Client{
			Timeout: cfg.OriginTimeout,
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				return errors.New("origin redirects are disabled")
			},
		},
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", e.health)
	mux.HandleFunc("/metrics", e.metrics)
	mux.HandleFunc("/live.m3u8", e.playlist)
	mux.HandleFunc("/", e.media)

	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           logging(mux),
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}
	log.Printf("cdn-edge listening on %s; origin=%s; cache=%dMiB", cfg.Listen, cfg.Origin, cfg.MaxCacheBytes>>20)
	log.Fatal(srv.ListenAndServe())
}

func loadConfig() (Config, error) {
	raw := strings.TrimSpace(os.Getenv("CDN_EDGE_ORIGIN"))
	if raw == "" {
		return Config{}, errors.New("CDN_EDGE_ORIGIN is required")
	}
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return Config{}, fmt.Errorf("invalid CDN_EDGE_ORIGIN: %q", raw)
	}
	u.Path = strings.TrimRight(u.Path, "/")
	cacheBytes, err := positiveInt64(getenv("CDN_EDGE_CACHE_BYTES", "536870912"))
	if err != nil { return Config{}, errors.New("CDN_EDGE_CACHE_BYTES must be a positive integer") }
	maxObject, err := positiveInt64(getenv("CDN_EDGE_MAX_OBJECT_BYTES", "33554432"))
	if err != nil { return Config{}, errors.New("CDN_EDGE_MAX_OBJECT_BYTES must be a positive integer") }
	ttl, err := time.ParseDuration(getenv("CDN_EDGE_SEGMENT_TTL", "30s"))
	if err != nil || ttl <= 0 { return Config{}, errors.New("CDN_EDGE_SEGMENT_TTL must be positive") }
	timeout, err := time.ParseDuration(getenv("CDN_EDGE_ORIGIN_TIMEOUT", "10s"))
	if err != nil || timeout <= 0 { return Config{}, errors.New("CDN_EDGE_ORIGIN_TIMEOUT must be positive") }
	return Config{Listen: getenv("CDN_EDGE_LISTEN", ":8080"), Origin: u, MaxCacheBytes: cacheBytes, MaxObjectBytes: maxObject, SegmentTTL: ttl, OriginTimeout: timeout}, nil
}

func (e *Edge) health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet { http.Error(w, "method not allowed", http.StatusMethodNotAllowed); return }
	w.Header().Set("Content-Type", "application/json")
	_, _ = fmt.Fprintf(w, `{"ok":true,"origin":%q}`, e.cfg.Origin.String())
}

func (e *Edge) metrics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet { http.Error(w, "method not allowed", http.StatusMethodNotAllowed); return }
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")
	_, _ = fmt.Fprintf(w, "cdn_edge_cache_hits %d\ncdn_edge_cache_misses %d\ncdn_edge_origin_errors %d\n", e.hits.Load(), e.misses.Load(), e.originErr.Load())
}

func (e *Edge) playlist(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet { http.Error(w, "method not allowed", http.StatusMethodNotAllowed); return }
	body, ct, err := e.fetchOrigin(r.Context(), "/live.m3u8", false)
	if err != nil { e.originErr.Add(1); http.Error(w, "origin playlist unavailable", http.StatusBadGateway); return }
	rewritten, err := rewritePlaylist(body, e.cfg.Origin)
	if err != nil { http.Error(w, "invalid HLS playlist", http.StatusBadGateway); return }
	w.Header().Set("Content-Type", playlistContentType(ct))
	w.Header().Set("Cache-Control", "no-store, max-age=0")
	w.Header().Set("X-CDN-Edge-Cache", "BYPASS")
	_, _ = w.Write(rewritten)
}

func (e *Edge) media(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead { http.Error(w, "method not allowed", http.StatusMethodNotAllowed); return }
	if !allowedMediaPath(r.URL.Path) { http.Error(w, "resource rejected", http.StatusForbidden); return }
	key := r.URL.RequestURI()
	if r.Method == http.MethodGet {
		if entry, ok := e.cache.get(key, time.Now()); ok {
			e.hits.Add(1)
			writeCached(w, entry)
			return
		}
	}
	e.misses.Add(1)
	body, ct, err := e.fetchSingleflight(r.Context(), r.URL.Path, r.URL.RawQuery)
	if err != nil { e.originErr.Add(1); http.Error(w, "origin media unavailable", http.StatusBadGateway); return }
	if r.Method == http.MethodGet {
		e.cache.put(cacheEntry{key: key, body: body, contentType: ct, expiresAt: time.Now().Add(e.cfg.SegmentTTL)})
	}
	w.Header().Set("Content-Type", mediaContentType(ct, r.URL.Path))
	w.Header().Set("Cache-Control", fmt.Sprintf("public, max-age=%d", int(e.cfg.SegmentTTL.Seconds())))
	w.Header().Set("X-CDN-Edge-Cache", "MISS")
	if r.Method == http.MethodGet { _, _ = w.Write(body) }
}

type flight struct { done chan struct{}; body []byte; ct string; err error }

func (e *Edge) fetchSingleflight(ctx context.Context, p, rawQuery string) ([]byte, string, error) {
	key := p + "?" + rawQuery
	f := &flight{done: make(chan struct{})}
	actual, loaded := e.inflight.LoadOrStore(key, f)
	if loaded {
		select { case <-actual.(*flight).done: return actual.(*flight).body, actual.(*flight).ct, actual.(*flight).err; case <-ctx.Done(): return nil, "", ctx.Err() }
	}
	f.body, f.ct, f.err = e.fetchOrigin(ctx, p, true)
	close(f.done)
	e.inflight.Delete(key)
	return f.body, f.ct, f.err
}

func (e *Edge) fetchOrigin(ctx context.Context, p string, _ bool) ([]byte, string, error) {
	target := *e.cfg.Origin
	target.Path = joinPath(e.cfg.Origin.Path, p)
	// Only allow the configured origin host; never turn this into an open proxy.
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil)
	if err != nil { return nil, "", err }
	req.Header.Set("Accept", "video/mp4,video/iso.segment,video/mp2t,application/octet-stream,application/vnd.apple.mpegurl")
	req.Header.Set("User-Agent", "tv49eastz-cdn-edge/1")
	resp, err := e.client.Do(req)
	if err != nil { return nil, "", err }
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 { io.Copy(io.Discard, io.LimitReader(resp.Body, 4096)); return nil, "", fmt.Errorf("origin status %d", resp.StatusCode) }
	body, err := io.ReadAll(io.LimitReader(resp.Body, e.cfg.MaxObjectBytes+1))
	if err != nil { return nil, "", err }
	if int64(len(body)) > e.cfg.MaxObjectBytes { return nil, "", errors.New("origin object exceeds configured limit") }
	return body, resp.Header.Get("Content-Type"), nil
}

func rewritePlaylist(body []byte, origin *url.URL) ([]byte, error) {
	scanner := bufio.NewScanner(strings.NewReader(string(body)))
	scanner.Buffer(make([]byte, 4096), 4<<20)
	var out strings.Builder
	first := true
	for scanner.Scan() {
		line := scanner.Text()
		if !first { out.WriteByte('\n') }; first = false
		trimmed := strings.TrimSpace(line)
		if trimmed != "" && !strings.HasPrefix(trimmed, "#") {
			u, err := url.Parse(trimmed); if err != nil { return nil, err }
			if u.IsAbs() && (u.Host != origin.Host || u.Scheme != origin.Scheme) { return nil, errors.New("playlist references another origin") }
			if u.IsAbs() { u.Scheme = ""; u.Host = "" }
			if u.Path == "" { return nil, errors.New("empty media URI") }
			line = u.RequestURI()
		}
		out.WriteString(line)
	}
	if err := scanner.Err(); err != nil { return nil, err }
	out.WriteByte('\n')
	return []byte(out.String()), nil
}

func allowedMediaPath(p string) bool {
	if p == "" || strings.Contains(p, "..") || strings.Contains(p, "//") || strings.ContainsAny(p, "\\\x00") { return false }
	lower := strings.ToLower(path.Clean(p))
	return strings.HasSuffix(lower, ".m3u8") || strings.HasSuffix(lower, ".m4s") || strings.HasSuffix(lower, ".mp4") || strings.HasSuffix(lower, ".ts")
}

func writeCached(w http.ResponseWriter, e cacheEntry) { w.Header().Set("Content-Type", mediaContentType(e.contentType, e.key)); w.Header().Set("Cache-Control", "public, max-age=30"); w.Header().Set("X-CDN-Edge-Cache", "HIT"); _, _ = w.Write(e.body) }
func playlistContentType(ct string) string { if ct != "" { return ct }; return "application/vnd.apple.mpegurl" }
func mediaContentType(ct, p string) string { if ct != "" { return ct }; if strings.HasSuffix(strings.ToLower(p), ".m4s") { return "video/iso.segment" }; if strings.HasSuffix(strings.ToLower(p), ".mp4") { return "video/mp4" }; if strings.HasSuffix(strings.ToLower(p), ".ts") { return "video/mp2t" }; return "application/octet-stream" }
func joinPath(base, p string) string { return path.Join("/", strings.Trim(base, "/"), strings.Trim(p, "/")) }
func positiveInt64(s string) (int64, error) { n, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64); if err != nil || n <= 0 { return 0, errors.New("invalid positive integer") }; return n, nil }
func getenv(k, d string) string { if v := strings.TrimSpace(os.Getenv(k)); v != "" { return v }; return d }
func logging(next http.Handler) http.Handler { return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { start := time.Now(); next.ServeHTTP(w, r); log.Printf("%s %s %s", r.Method, r.URL.RequestURI(), time.Since(start)) }) }
