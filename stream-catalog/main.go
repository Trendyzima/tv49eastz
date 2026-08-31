package main

import (
	"context"
	"crypto/subtle"
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
	creator     *creatorStore
	publishKey  string
	mu          sync.RWMutex
	cache       Catalog
}

func main() {
	creatorPath := env("CREATOR_REGISTRY_FILE", "./data/creators.json")
	creators, err := newCreatorStore(creatorPath)
	if err != nil {
		log.Fatalf("creator registry failed closed: %v", err)
	}
	s := &server{
		playlistURL: env("IPTV_ORG_PLAYLIST", defaultPlaylistURL),
		refresh:     time.Duration(envInt("CATALOG_REFRESH_MINUTES", 30)) * time.Minute,
		timeout:     time.Duration(envInt("CATALOG_TIMEOUT_SECONDS", 20)) * time.Second,
		maxBytes:    int64(envInt("CATALOG_MAX_BYTES", 32 << 20)),
		creator:     creators,
		publishKey:  strings.TrimSpace(os.Getenv("CREATOR_PUBLISH_KEY")),
	}
	if s.refresh <= 0 {
		s.refresh = 30 * time.Minute
	}
	if s.timeout <= 0 {
		s.timeout = 20 * time.Second
	}
	if err := s.refreshCatalog(); err != nil {
		log.Printf("initial IPTV catalog refresh failed: %v", err)
	}
	go s.refreshLoop()

	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.health)
	mux.HandleFunc("/v1/catalog", s.catalog)
	mux.HandleFunc("/v1/relay", s.relay)
	mux.HandleFunc("/v1/relay-asset", s.relayAsset)
	mux.HandleFunc("/v1/creators/channels", s.creatorChannels)

	addr := env("CATALOG_LISTEN", ":8790")
	log.Printf("TV 49 East channel catalog listening on %s", addr)
	log.Fatal((&http.Server{Addr: addr, Handler: securityHeaders(mux), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 20 * time.Second}).ListenAndServe())
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
	for _, ch := range s.creator.list() {
		if ch.ID == id {
			return Channel{ID: ch.ID, Name: ch.Name, Group: "TV East", Country: ch.Country, Language: ch.Language, Logo: ch.Logo, Stream: ch.Stream, Source: "fadcam", Relay: true}, true
		}
	}
	return Channel{}, false
}

func (s *server) health(w http.ResponseWriter, _ *http.Request) {
	c := s.snapshot()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "iptv_channels": len(c.Channels), "creator_channels": len(s.creator.list()), "updated": c.Updated})
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
	var merged []Channel
	for _, ch := range s.creator.list() {
		if region != "" && !strings.EqualFold(ch.Country, region) {
			continue
		}
		if query != "" && !strings.Contains(strings.ToLower(ch.Name), strings.ToLower(query)) {
			continue
		}
		merged = append(merged, Channel{ID: ch.ID, Name: ch.Name, Group: "TV East", Country: ch.Country, Language: ch.Language, Logo: ch.Logo, Stream: "/v1/relay?id=" + ch.ID, Source: "fadcam", Relay: true})
	}
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
		ch.Stream = "/v1/relay?id=" + ch.ID
		merged = append(merged, ch)
	}
	c.Channels = merged
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "public, max-age=60")
	_ = json.NewEncoder(w).Encode(c)
}

func (s *server) creatorChannels(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"channels": s.creator.list()})
	case http.MethodPost:
		if !s.authorizedPublisher(r) {
			http.Error(w, "publisher authorization required", http.StatusUnauthorized)
			return
		}
		var ch CreatorChannel
		dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10))
		if err := dec.Decode(&ch); err != nil {
			http.Error(w, "invalid channel payload", http.StatusBadRequest)
			return
		}
		if err := s.creator.upsert(ch); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(ch)
	case http.MethodDelete:
		if !s.authorizedPublisher(r) {
			http.Error(w, "publisher authorization required", http.StatusUnauthorized)
			return
		}
		id := strings.TrimSpace(r.URL.Query().Get("id"))
		if id == "" {
			http.Error(w, "missing channel id", http.StatusBadRequest)
			return
		}
		if err := s.creator.remove(id); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *server) authorizedPublisher(r *http.Request) bool {
	if s.publishKey == "" {
		return false
	}
	const prefix = "Bearer "
	got := r.Header.Get("Authorization")
	if !strings.HasPrefix(got, prefix) {
		return false
	}
	got = strings.TrimSpace(strings.TrimPrefix(got, prefix))
	if len(got) != len(s.publishKey) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(s.publishKey)) == 1
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
