package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func registerGatewayTestDevice(t *testing.T, deviceID, userID string) *TunnelRegistry {
	t.Helper()
	registry := defaultDeviceRegistry
	if err := registry.Register(DeviceRecord{
		DeviceID:    deviceID,
		PrincipalID: userID,
		Fingerprint: deviceID + "-fingerprint",
		Channels:    map[string]bool{"camera": true},
		Enabled:     true,
	}); err != nil {
		t.Fatal(err)
	}
	return NewTunnelRegistry()
}

func TestPlaylistAndMapRewrite(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/live.m3u8" {
			t.Errorf("path=%s", r.URL.Path)
		}
		_, _ = w.Write([]byte("#EXTM3U\n#EXT-X-MAP:URI=\"/init.mp4\"\n/seg-1.m4s\n"))
	}))
	defer up.Close()

	tunnels := registerGatewayTestDevice(t, "test-device-playlist", "api-key-user")
	if err := tunnels.Register("test-device-playlist", up.URL, up.Client().Transport); err != nil {
		t.Fatal(err)
	}
	g := &Gateway{
		cfg:     Config{Upstream: up.URL, CapabilityKey: "test-secret"},
		client:  &http.Client{Timeout: time.Second},
		tunnels: tunnels,
		policy:  AuthorizationPolicy{Registry: defaultDeviceRegistry},
	}
	s := Session{ID: "s", UserID: "api-key-user", DeviceID: "test-device-playlist", ChannelID: "camera", StreamID: "stream-1", IssuedAt: time.Now().Add(-time.Second), Expires: time.Now().Add(time.Hour)}
	g.sessions.Store("s", s)
	r := httptest.NewRequest("GET", "/stream/s/index.m3u8", nil)
	w := httptest.NewRecorder()
	g.stream(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("status=%d", w.Code)
	}
	body := w.Body.String()
	if strings.Contains(body, up.URL) || strings.Contains(body, "192.168.") {
		t.Fatal("upstream leaked")
	}
	if strings.Count(body, "/stream/s/resource/") != 2 {
		t.Fatalf("expected 2 rewritten refs: %s", body)
	}
}

func TestRejectUnsafeResource(t *testing.T) {
	up := httptest.NewServer(http.NotFoundHandler())
	defer up.Close()
	tunnels := registerGatewayTestDevice(t, "test-device-unsafe", "api-key-user")
	if err := tunnels.Register("test-device-unsafe", up.URL, up.Client().Transport); err != nil {
		t.Fatal(err)
	}
	g := &Gateway{
		cfg:     Config{Upstream: up.URL, CapabilityKey: "test-secret"},
		client:  &http.Client{Timeout: time.Second},
		tunnels: tunnels,
		policy:  AuthorizationPolicy{Registry: defaultDeviceRegistry},
	}
	g.sessions.Store("s", Session{ID: "s", UserID: "api-key-user", DeviceID: "test-device-unsafe", ChannelID: "camera", StreamID: "stream-1", IssuedAt: time.Now().Add(-time.Second), Expires: time.Now().Add(time.Hour)})
	bad := "aHR0cDovL2V2aWw="
	r := httptest.NewRequest("GET", "/stream/s/resource/"+bad, nil)
	w := httptest.NewRecorder()
	g.stream(w, r)
	if w.Code < 400 || w.Code >= 500 {
		t.Fatalf("unsafe resource status=%d", w.Code)
	}
}

func TestMutationBlocked(t *testing.T) {
	g := &Gateway{cfg: Config{APIKey: "k"}}
	h := g.middleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	for _, method := range []string{"POST", "PUT", "PATCH", "DELETE"} {
		r := httptest.NewRequest(method, "/stream/s/index.m3u8", nil)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != http.StatusMethodNotAllowed {
			t.Fatalf("%s=%d", method, w.Code)
		}
	}
}
