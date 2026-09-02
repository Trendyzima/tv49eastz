package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
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
	Listen               string
	CatalogURL           string
	CatalogKey           string
	TunnelURL            string
	CacheBytes           int64
	MaxObjectBytes       int64
	SegmentTTL           time.Duration
	OriginTimeout        time.Duration
	MaxOriginConcurrent  int
	TargetTTL            time.Duration
}

type originTarget struct {
	ChannelID  string `json:"channel_id"`
	DeviceID   string `json:"device_id"`
	StreamPath string `json:"stream_path"`
	Source     string `json:"source"`
}

type cacheEntry struct {
	body        []byte
	contentType string
	expires     time.Time
}

type cache struct {
	mu       sync.Mutex
	maxBytes int64
	bytes    int64
	items    map[string]cacheEntry
	order    []string
}

func newCache(maxBytes int64) *cache {
	if maxBytes <= 0 {
		maxBytes = 1
	}
	return &cache{maxBytes: maxBytes, items: make(map[string]cacheEntry)}
}

func (c *cache) get(key string, now time.Time) (cacheEntry, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	e, ok := c.items[key]
	if !ok {
		return cacheEntry{}, false
	}
	if !now.Before(e.expires) {
		c.removeLocked(key)
		return cacheEntry{}, false
	}
	return e, true
}

func (c *cache) put(key string, entry cacheEntry) {
	size := int64(len(entry.body))
	if size > c.maxBytes {
		return
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	if old, ok := c.items[key]; ok {
		c.bytes -= int64(len(old.body))
		c.removeOrderLocked(key)
	}
	c.items[key] = entry
	c.order = append(c.order, key)
	c.bytes += size

	for c.bytes > c.maxBytes && len(c.order) > 0 {
		victim := c.order[0]
		c.order = c.order[1:]
		if old, ok := c.items[victim]; ok {
			c.bytes -= int64(len(old.body))
			delete(c.items, victim)
		}
	}
}

func (c *cache) removeLocked(key string) {
	if entry, ok := c.items[key]; ok {
		c.bytes -= int64(len(entry.body))
		delete(c.items, key)
		c.removeOrderLocked(key)
	}
}

func (c *cache) removeOrderLocked(key string) {
	for i, candidate := range c.order {
		if candidate == key {
			c.order = append(c.order[:i], c.order[i+1:]...)
			return
		}
	}
}

func (c *cache) stats() (int64, int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.bytes, len(c.items)
}

type flight struct {
	done  chan struct{}
	entry cacheEntry
	err   error
}

type shield struct {
	cfg          Config
	client       *http.Client
	cache        *cache
	mu           sync.Mutex
	flights      map[string]*flight
	targets      map[string]originTarget
	targetExp    map[string]time.Time
	originSem    chan struct{}
	requests     uint64
	hits         uint64
	misses       uint64
	originFetches uint64
	originErrors uint64
	targetRefreshes uint64
}

func main() {
	cfg, err := loadConfig()
	if err != nil {
		log.Fatal(err)
	}

	s := newShield(cfg)
	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.health)
	mux.HandleFunc("/metrics", s.metrics)
	mux.HandleFunc("/channel/", s.channel)

	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           securityHeaders(mux),
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}

	log.Printf("TV 49 East origin shield listening on %s", cfg.Listen)
	log.Fatal(srv.ListenAndServe())
}

func loadConfig() (Config, error) {
	cfg := Config{
		Listen:              getenv("SHIELD_LISTEN", ":8795"),
		CatalogURL:          strings.TrimRight(strings.TrimSpace(os.Getenv("SHIELD_CATALOG_URL")), "/"),
		CatalogKey:          strings.TrimSpace(os.Getenv("SHIELD_CATALOG_KEY")),
		TunnelURL:           strings.TrimRight(strings.TrimSpace(os.Getenv("SHIELD_TUNNEL_URL")), "/"),
		CacheBytes:          positiveInt64(getenv("SHIELD_CACHE_BYTES", "536870912"), 512<<20),
		MaxObjectBytes:      positiveInt64(getenv("SHIELD_MAX_OBJECT_BYTES", "33554432"), 32<<20),
		SegmentTTL:          positiveDuration(getenv("SHIELD_SEGMENT_TTL", "30s"), 30*time.Second),
		OriginTimeout:       positiveDuration(getenv("SHIELD_ORIGIN_TIMEOUT", "10s"), 10*time.Second),
		MaxOriginConcurrent: int(positiveInt64(getenv("SHIELD_MAX_ORIGIN_CONCURRENCY", "256"), 256)),
		TargetTTL:           positiveDuration(getenv("SHIELD_TARGET_TTL", "30s"), 30*time.Second),
	}

	if cfg.CatalogURL == "" || cfg.CatalogKey == "" || cfg.TunnelURL == "" {
		return cfg, errors.New("SHIELD_CATALOG_URL, SHIELD_CATALOG_KEY and SHIELD_TUNNEL_URL are required")
	}

	catalogURL, err := url.Parse(cfg.CatalogURL)
	if err != nil || catalogURL.Scheme != "https" || catalogURL.Host == "" || catalogURL.User != nil || catalogURL.RawQuery != "" || catalogURL.Fragment != "" {
		return cfg, errors.New("SHIELD_CATALOG_URL must be a clean HTTPS base URL")
	}

	tunnelURL, err := url.Parse(cfg.TunnelURL)
	if err != nil || (tunnelURL.Scheme != "http" && tunnelURL.Scheme != "https") || tunnelURL.Host == "" || tunnelURL.User != nil || tunnelURL.RawQuery != "" || tunnelURL.Fragment != "" {
		return cfg, errors.New("SHIELD_TUNNEL_URL must be a clean HTTP(S) base URL")
	}

	return cfg, nil
}

func positiveInt64(value string, fallback int64) int64 {
	n, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
	if err != nil || n <= 0 {
		return fallback
	}
	return n
}

func positiveDuration(value string, fallback time.Duration) time.Duration {
	d, err := time.ParseDuration(strings.TrimSpace(value))
	if err != nil || d <= 0 {
		return fallback
	}
	return d
}

func getenv(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func newShield(cfg Config) *shield {
	transport := &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		MaxIdleConns: 512,
		MaxIdleConnsPerHost: 256,
		IdleConnTimeout: 30 * time.Second,
		DialContext: (&net.Dialer{Timeout: 5 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
	}
	return &shield{
		cfg: cfg,
		client: &http.Client{Transport: transport},
		cache: newCache(cfg.CacheBytes),
		flights: make(map[string]*flight),
		targets: make(map[string]originTarget),
		targetExp: make(map[string]time.Time),
		originSem: make(chan struct{}, maxInt(cfg.MaxOriginConcurrent, 1)),
	}
}

func maxInt(value, fallback int) int {
	if value < fallback {
		return fallback
	}
	return value
}

func (s *shield) channel(w http.ResponseWriter, r *http.Request) {
	atomic.AddUint64(&s.requests, 1)
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	channelID, asset, ok := parseChannelPath(r.URL.Path)
	if !ok || !validAsset(asset) {
		http.Error(w, "invalid or unsupported asset", http.StatusNotFound)
		return
	}
	if asset == "/live.m3u8" {
		s.servePlaylist(w, r, channelID)
		return
	}
	s.serveAsset(w, r, channelID, asset)
}

func parseChannelPath(p string) (string, string, bool) {
	const prefix = "/channel/"
	if !strings.HasPrefix(p, prefix) {
		return "", "", false
	}
	rest := strings.TrimPrefix(p, prefix)
	i := strings.IndexByte(rest, '/')
	if i <= 0 || i == len(rest)-1 {
		return "", "", false
	}
	id, asset := rest[:i], rest[i:]
	if len(id) > 256 || strings.ContainsAny(id, "/\\\x00\r\n") || strings.Contains(id, "..") {
		return "", "", false
	}
	return id, asset, true
}

func validAsset(p string) bool {
	if p == "" || !strings.HasPrefix(p, "/") || strings.ContainsAny(p, "\x00\r\n") || strings.Contains(p, "..") || strings.HasPrefix(p, "//") {
		return false
	}
	u, err := url.Parse(p)
	if err != nil || u.IsAbs() || u.Host != "" {
		return false
	}
	lower := strings.ToLower(u.Path)
	return u.Path == "/live.m3u8" || strings.HasSuffix(lower, ".m4s") || strings.HasSuffix(lower, ".mp4") || strings.HasSuffix(lower, ".ts") || strings.HasSuffix(lower, ".m3u8")
}

func (s *shield) resolve(ctx context.Context, channelID string) (originTarget, error) {
	s.mu.Lock()
	if target, ok := s.targets[channelID]; ok && time.Now().Before(s.targetExp[channelID]) {
		s.mu.Unlock()
		return target, nil
	}
	s.mu.Unlock()

	if s.cfg.CatalogURL == "" || s.cfg.CatalogKey == "" {
		return originTarget{}, errors.New("catalog origin resolution is not configured")
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.cfg.CatalogURL+"/v1/origin/channels?id="+url.QueryEscape(channelID), nil)
	if err != nil {
		return originTarget{}, err
	}
	req.Header.Set("X-Origin-Key", s.cfg.CatalogKey)

	resp, err := s.client.Do(req)
	if err != nil {
		return originTarget{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return originTarget{}, fmt.Errorf("catalog returned HTTP %d", resp.StatusCode)
	}

	var target originTarget
	if err := json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&target); err != nil {
		return originTarget{}, err
	}
	if target.ChannelID != channelID || !strings.EqualFold(target.Source, "fadcam") || !validDeviceID(target.DeviceID) || target.StreamPath != "/live.m3u8" {
		return originTarget{}, errors.New("catalog returned invalid FadCam origin")
	}

	s.mu.Lock()
	s.targets[channelID] = target
	s.targetExp[channelID] = time.Now().Add(s.cfg.TargetTTL)
	s.mu.Unlock()
	atomic.AddUint64(&s.targetRefreshes, 1)
	return target, nil
}

func validDeviceID(id string) bool {
	return id != "" && len(id) <= 256 && !strings.ContainsAny(id, "/\\\x00\r\n") && !strings.Contains(id, "..")
}

func (s *shield) invalidate(channelID string) {
	s.mu.Lock()
	delete(s.targets, channelID)
	delete(s.targetExp, channelID)
	s.mu.Unlock()
}

func (s *shield) originURL(target originTarget, asset string) string {
	return s.cfg.TunnelURL + "/device/" + url.PathEscape(target.DeviceID) + asset
}

func (s *shield) servePlaylist(w http.ResponseWriter, r *http.Request, channelID string) {
	target, err := s.resolve(r.Context(), channelID)
	if err != nil {
		http.Error(w, "channel origin unavailable", http.StatusServiceUnavailable)
		return
	}

	body, contentType, status, err := s.fetch(r.Context(), s.originURL(target, "/live.m3u8"), 8<<20)
	if err != nil {
		s.invalidate(channelID)
		target, resolveErr := s.resolve(r.Context(), channelID)
		if resolveErr == nil {
			body, contentType, status, err = s.fetch(r.Context(), s.originURL(target, "/live.m3u8"), 8<<20)
		}
	}
	if err != nil {
		atomic.AddUint64(&s.originErrors, 1)
		http.Error(w, "channel origin unavailable", http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", playlistContentType(contentType))
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-TV49East-Shield", "ORIGIN")
	w.WriteHeader(status)
	if r.Method != http.MethodHead {
		_, _ = w.Write(rewritePlaylist(body, channelID))
	}
}

func (s *shield) serveAsset(w http.ResponseWriter, r *http.Request, channelID, asset string) {
	key := channelID + "\n" + asset
	if entry, ok := s.cache.get(key, time.Now()); ok {
		atomic.AddUint64(&s.hits, 1)
		writeCached(w, entry, r.Method)
		return
	}
	atomic.AddUint64(&s.misses, 1)

	entry, err := s.singleflight(r.Context(), key, channelID, asset)
	if err != nil {
		atomic.AddUint64(&s.originErrors, 1)
		http.Error(w, "origin unavailable", http.StatusBadGateway)
		return
	}
	writeCached(w, entry, r.Method)
}

func (s *shield) singleflight(ctx context.Context, key, channelID, asset string) (cacheEntry, error) {
	if entry, ok := s.cache.get(key, time.Now()); ok {
		return entry, nil
	}

	s.mu.Lock()
	if current, ok := s.flights[key]; ok {
		s.mu.Unlock()
		select {
		case <-current.done:
			return current.entry, current.err
		case <-ctx.Done():
			return cacheEntry{}, ctx.Err()
		}
	}
	current := &flight{done: make(chan struct{})}
	s.flights[key] = current
	s.mu.Unlock()

	entry, err := s.fetchAsset(ctx, channelID, asset)

	s.mu.Lock()
	current.entry = entry
	current.err = err
	close(current.done)
	delete(s.flights, key)
	s.mu.Unlock()
	return entry, err
}

func (s *shield) fetchAsset(viewerCtx context.Context, channelID, asset string) (cacheEntry, error) {
	target, err := s.resolve(viewerCtx, channelID)
	if err != nil {
		return cacheEntry{}, err
	}

	fetchCtx, cancel := context.WithTimeout(context.Background(), s.cfg.OriginTimeout)
	defer cancel()

	entry, err := s.fetchEntry(fetchCtx, s.originURL(target, asset))
	if err == nil {
		s.cache.put(channelID+"\n"+asset, entry)
		return entry, nil
	}

	s.invalidate(channelID)
	target, err = s.resolve(viewerCtx, channelID)
	if err != nil {
		return cacheEntry{}, err
	}
	fetchCtx, cancel = context.WithTimeout(context.Background(), s.cfg.OriginTimeout)
	defer cancel()
	entry, err = s.fetchEntry(fetchCtx, s.originURL(target, asset))
	if err != nil {
		return cacheEntry{}, err
	}
	s.cache.put(channelID+"\n"+asset, entry)
	return entry, nil
}

func (s *shield) fetchEntry(ctx context.Context, rawURL string) (cacheEntry, error) {
	select {
	case s.originSem <- struct{}{}:
		defer func() { <-s.originSem }()
	case <-ctx.Done():
		return cacheEntry{}, ctx.Err()
	}

	atomic.AddUint64(&s.originFetches, 1)
	body, contentType, status, err := s.fetch(ctx, rawURL, s.cfg.MaxObjectBytes)
	if err != nil {
		return cacheEntry{}, err
	}
	if status < 200 || status >= 300 {
		return cacheEntry{}, fmt.Errorf("origin returned HTTP %d", status)
	}
	return cacheEntry{body: body, contentType: contentType, expires: time.Now().Add(s.cfg.SegmentTTL)}, nil
}

func (s *shield) fetch(ctx context.Context, rawURL string, maxBytes int64) ([]byte, string, int, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, "", 0, err
	}
	req.Header.Set("User-Agent", "TV49East-Origin-Shield/1.0")
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, "", 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, resp.Header.Get("Content-Type"), resp.StatusCode, fmt.Errorf("origin returned HTTP %d", resp.StatusCode)
	}
	if maxBytes <= 0 {
		maxBytes = 1
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBytes+1))
	if err != nil {
		return nil, "", resp.StatusCode, err
	}
	if int64(len(body)) > maxBytes {
		return nil, "", resp.StatusCode, errors.New("origin object exceeds configured limit")
	}
	return body, resp.Header.Get("Content-Type"), resp.StatusCode, nil
}

func writeCached(w http.ResponseWriter, entry cacheEntry, method string) {
	w.Header().Set("Content-Type", mediaContentType(entry.contentType, ""))
	w.Header().Set("Cache-Control", "public, max-age=30, immutable")
	w.Header().Set("X-TV49East-Shield", "CACHE-HIT")
	w.Header().Set("Content-Length", strconv.Itoa(len(entry.body)))
	w.WriteHeader(http.StatusOK)
	if method != http.MethodHead {
		_, _ = w.Write(entry.body)
	}
}

func playlistContentType(contentType string) string {
	if contentType != "" {
		return contentType
	}
	return "application/vnd.apple.mpegurl"
}

func mediaContentType(contentType, p string) string {
	if contentType != "" {
		return contentType
	}
	switch strings.ToLower(path.Ext(p)) {
	case ".m4s":
		return "video/iso.segment"
	case ".mp4":
		return "video/mp4"
	case ".ts":
		return "video/mp2t"
	case ".m3u8":
		return "application/vnd.apple.mpegurl"
	default:
		return "application/octet-stream"
	}
}

func rewritePlaylist(body []byte, channelID string) []byte {
	lines := strings.Split(string(body), "\n")
	for i, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		lines[i] = rewriteHLSLine(line, channelID)
	}
	return []byte(strings.Join(lines, "\n"))
}

func rewriteHLSLine(line, channelID string) string {
	if strings.HasPrefix(strings.TrimSpace(line), "#") {
		return rewriteTagURIs(line, channelID)
	}
	return shieldAssetURL(strings.TrimSpace(line), channelID)
}

func rewriteTagURIs(line, channelID string) string {
	for {
		start := strings.Index(line, "URI=\"")
		if start < 0 {
			return line
		}
		valueStart := start + len("URI=\"")
		rest := line[valueStart:]
		end := strings.IndexByte(rest, '"')
		if end < 0 {
			return line
		}
		raw := rest[:end]
		rewritten := shieldAssetURL(raw, channelID)
		line = line[:valueStart] + rewritten + line[valueStart+end:]
	}
}

func shieldAssetURL(raw, channelID string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return raw
	}
	u, err := url.Parse(raw)
	if err != nil || u.IsAbs() || u.Host != "" || strings.ContainsAny(raw, "\x00\r\n") || strings.Contains(raw, "..") {
		return "# UNSAFE_RESOURCE_REJECTED"
	}
	if !strings.HasPrefix(u.Path, "/") {
		u.Path = "/" + u.Path
	}
	if !validAsset(u.EscapedPath()) {
		return "# UNSAFE_RESOURCE_REJECTED"
	}
	return "/channel/" + url.PathEscape(channelID) + u.EscapedPath() + func() string {
		if u.RawQuery != "" {
			return "?" + u.RawQuery
		}
		return ""
	}()
}

func (s *shield) health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	bytes, objects := s.cache.stats()
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"ok": true,
		"cache_bytes": bytes,
		"cache_objects": objects,
		"origin_fetches": atomic.LoadUint64(&s.originFetches),
	})
}

func (s *shield) metrics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	bytes, objects := s.cache.stats()
	fmt.Fprintf(w, "# TYPE tv49east_shield_requests_total counter\ntv49east_shield_requests_total %d\n", atomic.LoadUint64(&s.requests))
	fmt.Fprintf(w, "# TYPE tv49east_shield_cache_hits_total counter\ntv49east_shield_cache_hits_total %d\n", atomic.LoadUint64(&s.hits))
	fmt.Fprintf(w, "# TYPE tv49east_shield_cache_misses_total counter\ntv49east_shield_cache_misses_total %d\n", atomic.LoadUint64(&s.misses))
	fmt.Fprintf(w, "# TYPE tv49east_shield_origin_fetches_total counter\ntv49east_shield_origin_fetches_total %d\n", atomic.LoadUint64(&s.originFetches))
	fmt.Fprintf(w, "# TYPE tv49east_shield_origin_errors_total counter\ntv49east_shield_origin_errors_total %d\n", atomic.LoadUint64(&s.originErrors))
	fmt.Fprintf(w, "# TYPE tv49east_shield_target_refreshes_total counter\ntv49east_shield_target_refreshes_total %d\n", atomic.LoadUint64(&s.targetRefreshes))
	fmt.Fprintf(w, "tv49east_shield_cache_bytes %d\n", bytes)
	fmt.Fprintf(w, "tv49east_shield_cache_objects %d\n", objects)
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}
