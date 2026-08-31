package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestPublicStreamBoundaryDoesNotExposeControlEndpointsOrOrigin(t *testing.T) {
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/live.m3u8":
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			_, _ = io.WriteString(w, "#EXTM3U\n#EXTINF:1,\n/segment-1.m4s\n")
		case "/segment-1.m4s":
			w.Header().Set("Content-Type", "video/iso.segment")
			_, _ = io.WriteString(w, "MEDIA-BYTES")
		default:
			http.NotFound(w, r)
		}
	}))
	defer origin.Close()

	if err := defaultDeviceRegistry.Register(DeviceRecord{
		DeviceID:    "device-a",
		PrincipalID: "u",
		Fingerprint: "integration-device-a",
		Channels:    map[string]bool{"camera": true},
		Enabled:     true,
	}); err != nil {
		t.Fatal(err)
	}

	g := &Gateway{
		cfg:     Config{CapabilityKey: "test-secret", Upstream: origin.URL},
		client:  origin.Client(),
		tunnels: NewTunnelRegistry(),
		policy:  AuthorizationPolicy{Registry: defaultDeviceRegistry},
	}
	if err := g.tunnels.Register("device-a", origin.URL, origin.Client().Transport); err != nil {
		t.Fatal(err)
	}
	s := Session{
		ID:          "s",
		UserID:      "u",
		DeviceID:    "device-a",
		Fingerprint: "integration-device-a",
		ChannelID:   "camera",
		StreamID:    "stream-a",
		IssuedAt:    time.Now().UTC(),
		Expires:     time.Now().UTC().Add(time.Minute),
	}
	g.sessions.Store(s.ID, s)
	g.sessionCount.Add(1)

	r := httptest.NewRequest("GET", "/stream/s/index.m3u8", nil)
	w := httptest.NewRecorder()
	g.stream(w, r)
	body := w.Body.String()
	if w.Code != http.StatusOK {
		t.Fatalf("playlist status=%d body=%s", w.Code, body)
	}
	if strings.Contains(body, origin.URL) || strings.Contains(body, "192.168.100.5:8080") {
		t.Fatal("private origin leaked into public playlist")
	}
	if strings.Contains(body, "/status") || strings.Contains(body, "/audio/volume") {
		t.Fatal("control endpoint leaked")
	}

	for _, path := range []string{"/stream/s/resource/bad", "/stream/s/resource/"} {
		rr := httptest.NewRequest("GET", path, nil)
		ww := httptest.NewRecorder()
		g.stream(ww, rr)
		if ww.Code == http.StatusOK {
			t.Fatalf("invalid resource accepted: %s", path)
		}
	}
}
