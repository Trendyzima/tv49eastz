package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

type server struct {
	playlistURL string
	refresh     time.Duration
	timeout     time.Duration
	maxBytes    int64
	mu          sync.RWMutex
	cache       Catalog
}

func main() {
	s := &server{
		playlistURL: env("IPTV_ORG_PLAYLIST", defaultPlaylistURL),
		refresh:     time.Duration(envInt("CATALOG_REFRESH_MINUTES", 30)) * time.Minute,
		timeout:     time.Duration(envInt("CATALOG_TIMEOUT_SECONDS", 20)) * time.Second,
		maxBytes:    int64(envInt("CATALOG_MAX_BYTES", 32 << 20)),
	}
	if s.refresh <= 0 {
		s.refresh = 30 * time.Minute
	}
	if s.timeout <= 0 {
		s.timeout = 20 * time.Second
	}
	if err := s.refreshCatalog(); err != nil {
		log.Printf("initial catalog refresh failed: %v", err)
	}
	go s.refreshLoop()

	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.health)
	mux.HandleFunc("/v1/catalog", s.catalog)
	mux.HandleFunc("/v1/relay", s.relay)
	mux.HandleFunc("/v1/relay-asset", s.relayAsset)

	addr := env("CATALOG_LISTEN", ":8790")
	log.Printf("TV 49 East channel catalog listening on %s", addr)
	log.Fatal((&http.Server{
		Addr:              addr,
		Handler:           securityHeaders(mux),
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       20 * time.Second,
	}).ListenAndServe())
}

func (s *server) refreshCatalog() error {
	c, err := fetchCatalog(context.Background(), s.playlistURL, s.timeout, s.maxBytes)
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.cache = c
	s.mu.Unlock()
	return nil
}

func (s *server) refreshLoop() {
	t := time.NewTicker(s.refresh)
	defer t.Stop()
	for range t.C {
		if err := s.refreshCatalog(); err != nil {
			log.Printf("catalog refresh failed; retaining last known-good catalog: %v", err)
		}
	}
}

func (s *server) snapshot() Catalog {
	s.mu.RLock()
	defer s.mu.RUnlock()
	channels := append([]Channel(nil), s.cache.Channels...)
	return Catalog{Channels: channels, Updated: s.cache.Updated}
}

func (s *server) findChannel(id string) (Channel, bool) {
	c := s.snapshot()
	for _, ch := range c.Channels {
		if ch.ID == id {
			return ch, true
		}
	}
	return Channel{}, false
}

func (s *server) health(w http.ResponseWriter, _ *http.Request) {
	c := s.snapshot()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "channels": len(c.Channels), "updated": c.Updated})
}

func (s *server) catalog(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	c := s.snapshot()
	region := strings.TrimSpace(r.URL.Query().Get("country"))
	group := strings.TrimSpace(r.URL.Query().Get("group"))
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	if region != "" || group != "" || query != "" {
		filtered := make([]Channel, 0, len(c.Channels))
		for _, ch := range c.Channels {
			if region != "" && !strings.EqualFold(ch.Country, region) {
				continue
			}
			if group != "" && !strings.EqualFold(ch.Group, group) {
				continue
			}
			if query != "" && !strings.Contains(strings.ToLower(ch.Name), strings.ToLower(query)) {
				continue
			}
			// Never expose upstream URLs to the receiver. Playback always goes through TV 49 East.
			ch.Stream = "/v1/relay?id=" + ch.ID
			filtered = append(filtered, ch)
		}
		c.Channels = filtered
	} else {
		for i := range c.Channels {
			c.Channels[i].Stream = "/v1/relay?id=" + c.Channels[i].ID
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "public, max-age=60")
	_ = json.NewEncoder(w).Encode(c)
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}

func env(k, d string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return d
}

func envInt(k string, d int) int {
	v, err := strconv.Atoi(os.Getenv(k))
	if err != nil {
		return d
	}
	return v
}
