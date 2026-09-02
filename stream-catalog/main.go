package main

import (
	"crypto/subtle"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

type server struct {
	creator     *creatorStore
	publishKey  string
	relaySecret string
	originKey   string
	timeout     time.Duration
}

type OriginTarget struct {
	ChannelID  string `json:"channel_id"`
	DeviceID   string `json:"device_id"`
	StreamPath string `json:"stream_path"`
	Source     string `json:"source"`
}

func main() {
	creatorPath := env("CREATOR_REGISTRY_FILE", "./data/creators.json")
	creators, err := newCreatorStore(creatorPath)
	if err != nil {
		log.Fatalf("creator registry failed closed: %v", err)
	}

	timeout := positiveDuration(env("CATALOG_HTTP_TIMEOUT", "10s"), 10*time.Second)
	s := &server{
		creator:     creators,
		publishKey:  strings.TrimSpace(os.Getenv("CREATOR_PUBLISH_KEY")),
		relaySecret: strings.TrimSpace(os.Getenv("RELAY_SIGNING_SECRET")),
		originKey:   strings.TrimSpace(os.Getenv("ORIGIN_RESOLVE_KEY")),
		timeout:     timeout,
	}
	if s.relaySecret == "" {
		log.Fatal("RELAY_SIGNING_SECRET is required")
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", s.health)
	mux.HandleFunc("/v1/catalog", s.catalog)
	mux.HandleFunc("/v1/relay", s.relay)
	mux.HandleFunc("/v1/relay-asset", s.relayAsset)
	mux.HandleFunc("/v1/creators/channels", s.creatorChannels)
	mux.HandleFunc("/v1/origin/channels", s.originChannel)

	addr := env("CATALOG_LISTEN", ":8790")
	log.Printf("TV 49 East FadCam catalog/relay listening on %s", addr)
	log.Fatal((&http.Server{
		Addr:              addr,
		Handler:           securityHeaders(mux),
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       30 * time.Second,
		WriteTimeout:      timeout,
	}).ListenAndServe())
}

func (s *server) findChannel(id string) (Channel, bool) {
	for _, ch := range s.creator.list() {
		if ch.ID == id {
			return Channel{ID: ch.ID, Name: ch.Name, Group: "FadCam", Country: ch.Country, Language: ch.Language, Logo: ch.Logo, Stream: "/v1/relay?id=" + ch.ID, Source: "fadcam", Relay: true}, true
		}
	}
	return Channel{}, false
}

func (s *server) health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "fadcam_channels": len(s.creator.list()), "updated": time.Now().UTC()})
}

func (s *server) catalog(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	region := strings.TrimSpace(r.URL.Query().Get("country"))
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	out := make([]Channel, 0)
	for _, ch := range s.creator.list() {
		if region != "" && !strings.EqualFold(ch.Country, region) {
			continue
		}
		if query != "" && !strings.Contains(strings.ToLower(ch.Name), strings.ToLower(query)) {
			continue
		}
		out = append(out, Channel{ID: ch.ID, Name: ch.Name, Group: "FadCam", Country: ch.Country, Language: ch.Language, Logo: ch.Logo, Stream: "/v1/relay?id=" + ch.ID, Source: "fadcam", Relay: true})
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "public, max-age=5")
	_ = json.NewEncoder(w).Encode(Catalog{Channels: out, Updated: time.Now().UTC()})
}

func (s *server) originChannel(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.authorizedOrigin(r) {
		http.Error(w, "origin authorization required", http.StatusUnauthorized)
		return
	}
	id := strings.TrimSpace(r.URL.Query().Get("id"))
	if id == "" {
		http.Error(w, "missing channel id", http.StatusBadRequest)
		return
	}
	for _, ch := range s.creator.list() {
		if ch.ID != id {
			continue
		}
		if !strings.EqualFold(ch.Source, "fadcam") || ch.DeviceID == "" || ch.StreamPath != "/live.m3u8" {
			http.Error(w, "channel origin is invalid", http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(OriginTarget{ChannelID: ch.ID, DeviceID: ch.DeviceID, StreamPath: ch.StreamPath, Source: ch.Source})
		return
	}
	http.Error(w, "channel not found", http.StatusNotFound)
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

func (s *server) authorizedOrigin(r *http.Request) bool {
	if s.originKey == "" {
		return false
	}
	got := strings.TrimSpace(r.Header.Get("X-Origin-Key"))
	if len(got) != len(s.originKey) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(s.originKey)) == 1
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func positiveDuration(v string, d time.Duration) time.Duration {
	x, err := time.ParseDuration(strings.TrimSpace(v))
	if err != nil || x <= 0 {
		return d
	}
	return x
}
