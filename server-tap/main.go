package main

import (
	"bufio"
	"context"
	"encoding/base64"
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
	"regexp"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

type Config struct {
	Listen            string
	Upstream          *url.URL
	Timeout           time.Duration
	MaxPlaylistBytes  int64
	MaxProxyBodyBytes int64
}

type Server struct {
	cfg      Config
	client   *http.Client
	requests atomic.Uint64
	errors   atomic.Uint64
}

var uriAttrRE = regexp.MustCompile(`URI="([^"]+)"`)

func main() {
	cfg, err := loadConfig()
	if err != nil {
		log.Fatal(err)
	}

	s := &Server{
		cfg: cfg,
		client: &http.Client{
			Timeout: cfg.Timeout,
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				if len(via) == 0 {
					return nil
				}
				return errors.New("upstream redirects are disabled")
			},
		},
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.health)
	mux.HandleFunc("/status", s.status)
	mux.HandleFunc("/live.m3u8", s.playlist)
	mux.HandleFunc("/init.mp4", s.initSegment)
	mux.HandleFunc("/audio/volume", s.volume)
	mux.HandleFunc("/hls/", s.hlsResource)
	mux.HandleFunc("/", s.root)

	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           securityHeaders(logging(mux, s)),
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}

	log.Printf("server-tap listening on %s; upstream=%s", cfg.Listen, cfg.Upstream.String())
	log.Printf("read-only policy: GET only; control endpoints are never forwarded")
	log.Fatal(srv.ListenAndServe())
}

func loadConfig() (Config, error) {
	rawUpstream := strings.TrimSpace(os.Getenv("TAP_UPSTREAM"))
	var u *url.URL
	var err error
	if rawUpstream != "" {
		u, err = url.Parse(rawUpstream)
		if err != nil || u.Scheme != "http" && u.Scheme != "https" || u.Host == "" || u.User != nil {
			return Config{}, fmt.Errorf("invalid TAP_UPSTREAM: %q", rawUpstream)
		}
		u.Path = strings.TrimRight(u.Path, "/")
		if u.RawQuery != "" || u.Fragment != "" {
			return Config{}, errors.New("TAP_UPSTREAM must not contain a query or fragment")
		}
		log.Printf("server-tap using explicit TAP_UPSTREAM=%s", u.String())
	} else {
		// Zero-configuration production path: discover the FadCam HLS server
		// locally. This removes the need to provision a changing phone/LAN IP.
		// The discovery code checks loopback first, then directly-connected LANs.
		u, err = discoverFadCamWithRetry(context.Background())
		if err != nil {
			return Config{}, err
		}
		log.Printf("server-tap auto-discovered FadCam upstream=%s", u.String())
	}

	timeout, err := time.ParseDuration(getenv("TAP_TIMEOUT", "10s"))
	if err != nil || timeout <= 0 {
		return Config{}, errors.New("TAP_TIMEOUT must be a positive duration")
	}

	maxPlaylist, err := parsePositiveInt64(getenv("TAP_MAX_PLAYLIST_BYTES", "1048576"))
	if err != nil {
		return Config{}, errors.New("TAP_MAX_PLAYLIST_BYTES must be a positive integer")
	}

	maxBody, err := parsePositiveInt64(getenv("TAP_MAX_PROXY_BODY_BYTES", "67108864"))
	if err != nil {
		return Config{}, errors.New("TAP_MAX_PROXY_BODY_BYTES must be a positive integer")
	}

	return Config{
		Listen:            getenv("TAP_LISTEN", "127.0.0.1:8788"),
		Upstream:          u,
		Timeout:           timeout,
		MaxPlaylistBytes:  maxPlaylist,
		MaxProxyBodyBytes: maxBody,
	}, nil
}

func discoverFadCamWithRetry(ctx context.Context) (*url.URL, error) {
	for {
		u, err := discoverFadCamUpstream(ctx)
		if err == nil {
			return u, nil
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		log.Printf("FadCam auto-discovery: %v; retrying", err)
		select {
		case <-time.After(2 * time.Second):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	if !requireGET(w, r) {
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":       true,
		"upstream": s.cfg.Upstream.Host,
		"requests": s.requests.Load(),
		"errors":   s.errors.Load(),
	})
}

func (s *Server) status(w http.ResponseWriter, r *http.Request) {
	if !requireGET(w, r) {
		return
	}
	s.proxyGET(w, r, "/status", false)
}

func (s *Server) initSegment(w http.ResponseWriter, r *http.Request) {
	if !requireGET(w, r) {
		return
	}
	target := *s.cfg.Upstream
	target.Path = joinURLPath(s.cfg.Upstream.Path, "/init.mp4")
	s.streamGET(w, r, &target)
}

func (s *Server) volume(w http.ResponseWriter, r *http.Request) {
	if !requireGET(w, r) {
		return
	}
	s.proxyGET(w, r, "/audio/volume", false)
}

func (s *Server) playlist(w http.ResponseWriter, r *http.Request) {
	if !requireGET(w, r) {
		return
	}

	body, contentType, status, err := s.fetchGET(r.Context(), "/live.m3u8", s.cfg.MaxPlaylistBytes)
	if err != nil {
		s.errors.Add(1)
		http.Error(w, "upstream playlist unavailable", upstreamStatus(err))
		return
	}

	rewritten, err := s.rewritePlaylist(body)
	if err != nil {
		s.errors.Add(1)
		http.Error(w, "invalid upstream HLS playlist", http.StatusBadGateway)
		return
	}

	w.Header().Set("Content-Type", normalizePlaylistContentType(contentType))
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Server-Tap", "readonly")
	w.WriteHeader(status)
	_, _ = w.Write(rewritten)
}

func (s *Server) hlsResource(w http.ResponseWriter, r *http.Request) {
	if !requireGET(w, r) {
		return
	}
	encoded := strings.TrimPrefix(r.URL.Path, "/hls/")
	if encoded == "" || strings.Contains(encoded, "/") {
		http.Error(w, "invalid HLS resource", http.StatusBadRequest)
		return
	}

	raw, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil || len(raw) > 4096 {
		http.Error(w, "invalid HLS resource", http.StatusBadRequest)
		return
	}
	target, err := url.Parse(string(raw))
	if err != nil || !s.sameOrigin(target) || !isAllowedMediaPath(target.Path) {
		http.Error(w, "HLS resource rejected", http.StatusForbidden)
		return
	}

	if target.Path == "/live.m3u8" || strings.HasSuffix(strings.ToLower(target.Path), ".m3u8") {
		body, _, status, err := s.fetchURL(r.Context(), target, s.cfg.MaxPlaylistBytes)
		if err != nil {
			s.errors.Add(1)
			http.Error(w, "upstream media unavailable", upstreamStatus(err))
			return
		}
		body, err = s.rewritePlaylist(body)
		if err != nil {
			s.errors.Add(1)
			http.Error(w, "invalid upstream HLS playlist", http.StatusBadGateway)
			return
		}
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Server-Tap", "readonly")
		w.WriteHeader(status)
		_, _ = w.Write(body)
		return
	}

	s.streamGET(w, r, target)
}

func (s *Server) streamGET(w http.ResponseWriter, r *http.Request, target *url.URL) {
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, target.String(), nil)
	if err != nil {
		s.errors.Add(1)
		http.Error(w, "invalid upstream request", http.StatusBadGateway)
		return
	}
	req.Header.Set("Accept", "video/mp4,video/iso.segment,video/mp2t,application/octet-stream")
	req.Header.Set("User-Agent", "tv49eastz-server-tap/1")
	resp, err := s.client.Do(req)
	if err != nil {
		s.errors.Add(1)
		http.Error(w, "upstream media unavailable", upstreamStatus(err))
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		http.Error(w, "upstream media unavailable", http.StatusBadGateway)
		return
	}
	if ct := resp.Header.Get("Content-Type"); ct != "" {
		w.Header().Set("Content-Type", ct)
	} else {
		w.Header().Set("Content-Type", normalizeMediaContentType("", target.Path))
	}
	if cl := resp.Header.Get("Content-Length"); cl != "" {
		w.Header().Set("Content-Length", cl)
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Server-Tap", "readonly")
	w.WriteHeader(resp.StatusCode)
	_, err = io.Copy(w, io.LimitReader(resp.Body, s.cfg.MaxProxyBodyBytes+1))
	if err != nil {
		log.Printf("media stream copy error: %v", err)
	}
}

func (s *Server) root(w http.ResponseWriter, r *http.Request) {
	if !requireGET(w, r) {
		return
	}
	http.Error(w, "server-tap: use /health, /status, or /live.m3u8", http.StatusNotFound)
}

func (s *Server) proxyGET(w http.ResponseWriter, r *http.Request, p string, rewrite bool) {
	body, contentType, status, err := s.fetchGET(r.Context(), p, s.cfg.MaxProxyBodyBytes)
	if err != nil {
		s.errors.Add(1)
		http.Error(w, "upstream request unavailable", upstreamStatus(err))
		return
	}
	if rewrite {
		body, err = s.rewritePlaylist(body)
		if err != nil {
			s.errors.Add(1)
			http.Error(w, "invalid upstream response", http.StatusBadGateway)
			return
		}
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Server-Tap", "readonly")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func (s *Server) fetchGET(ctx context.Context, p string, limit int64) ([]byte, string, int, error) {
	target := *s.cfg.Upstream
	target.Path = joinURLPath(s.cfg.Upstream.Path, p)
	target.RawQuery = ""
	return s.fetchURL(ctx, &target, limit)
}

func (s *Server) fetchURL(ctx context.Context, target *url.URL, limit int64) ([]byte, string, int, error) {
	if !s.sameOrigin(target) {
		return nil, "", 0, errors.New("target origin mismatch")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil)
	if err != nil {
		return nil, "", 0, err
	}
	req.Header.Set("Accept", "application/vnd.apple.mpegurl,video/mp4,video/iso.segment,application/octet-stream,application/json,text/plain;q=0.8")
	req.Header.Set("User-Agent", "tv49eastz-server-tap/1")

	resp, err := s.client.Do(req)
	if err != nil {
		return nil, "", 0, err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return nil, resp.Header.Get("Content-Type"), resp.StatusCode, fmt.Errorf("upstream status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return nil, "", 0, err
	}
	if int64(len(body)) > limit {
		return nil, "", 0, errors.New("upstream body exceeds configured limit")
	}
	return body, resp.Header.Get("Content-Type"), resp.StatusCode, nil
}

func (s *Server) rewritePlaylist(body []byte) ([]byte, error) {
	scanner := bufio.NewScanner(strings.NewReader(string(body)))
	scanner.Buffer(make([]byte, 4096), int(s.cfg.MaxPlaylistBytes))

	var out strings.Builder
	first := true
	for scanner.Scan() {
		line := scanner.Text()
		if !first {
			out.WriteByte('\n')
		}
		first = false
		rewritten, err := s.rewritePlaylistLine(line)
		if err != nil {
			return nil, err
		}
		out.WriteString(rewritten)
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	out.WriteByte('\n')
	return []byte(out.String()), nil
}

func (s *Server) rewritePlaylistLine(line string) (string, error) {
	matches := uriAttrRE.FindAllStringSubmatchIndex(line, -1)
	if len(matches) > 0 {
		var b strings.Builder
		last := 0
		for _, m := range matches {
			b.WriteString(line[last:m[2]])
			rewritten, err := s.rewriteURI(line[m[2]:m[3]])
			if err != nil {
				return "", err
			}
			b.WriteString(`URI="`)
			b.WriteString(rewritten)
			b.WriteByte('"')
			last = m[1]
		}
		b.WriteString(line[last:])
		line = b.String()
	}

	trimmed := strings.TrimSpace(line)
	if trimmed != "" && !strings.HasPrefix(trimmed, "#") {
		rewritten, err := s.rewriteURI(trimmed)
		if err != nil {
			return "", err
		}
		line = rewritten
	}
	return line, nil
}

func (s *Server) rewriteURI(raw string) (string, error) {
	ref, err := url.Parse(raw)
	if err != nil {
		return "", err
	}
	resolved := s.cfg.Upstream.ResolveReference(ref)
	if !s.sameOrigin(resolved) || !isAllowedMediaPath(resolved.Path) {
		return "", errors.New("playlist URI points outside allowed upstream media")
	}
	encoded := base64.RawURLEncoding.EncodeToString([]byte(resolved.String()))
	return "/hls/" + encoded, nil
}

func (s *Server) sameOrigin(u *url.URL) bool {
	return strings.EqualFold(u.Scheme, s.cfg.Upstream.Scheme) && strings.EqualFold(u.Host, s.cfg.Upstream.Host)
}

func isAllowedMediaPath(p string) bool {
	if p == "/live.m3u8" || p == "/init.mp4" || strings.HasSuffix(strings.ToLower(p), ".m3u8") {
		return true
	}
	ext := strings.ToLower(path.Ext(p))
	return ext == ".m4s" || ext == ".mp4" || ext == ".ts" || ext == ".aac" || ext == ".m4a" || ext == ".webm"
}

func joinURLPath(base, suffix string) string {
	if base == "" || base == "/" {
		return suffix
	}
	return strings.TrimRight(base, "/") + "/" + strings.TrimLeft(suffix, "/")
}

func requireGET(w http.ResponseWriter, r *http.Request) bool {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		http.Error(w, "server-tap is read-only; only GET is permitted", http.StatusMethodNotAllowed)
		return false
	}
	return true
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

func logging(next http.Handler, s *Server) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.requests.Add(1)
		start := time.Now()
		rw := &statusWriter{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rw, r)
		log.Printf("%s %s %d %s", r.Method, r.URL.Path, rw.status, time.Since(start).Round(time.Millisecond))
	})
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func normalizePlaylistContentType(ct string) string {
	if ct == "" {
		return "application/vnd.apple.mpegurl"
	}
	return ct
}

func normalizeMediaContentType(ct, p string) string {
	if ct != "" {
		return ct
	}
	switch strings.ToLower(path.Ext(p)) {
	case ".mp4", ".m4s", ".m4a":
		return "video/mp4"
	case ".ts":
		return "video/mp2t"
	case ".m3u8":
		return "application/vnd.apple.mpegurl"
	default:
		return "application/octet-stream"
	}
}

func upstreamStatus(err error) int {
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return http.StatusGatewayTimeout
	}
	return http.StatusBadGateway
}

func getenv(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func parsePositiveInt64(v string) (int64, error) {
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n <= 0 {
		return 0, errors.New("not positive")
	}
	return n, nil
}
