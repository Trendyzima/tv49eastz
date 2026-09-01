package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func TestTunnelRegistryLazyProxyWiring(t *testing.T) {
	var gotPath string
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		w.Header().Set("Content-Type", "text/plain")
		_, _ = io.WriteString(w, "ok")
	}))
	defer proxy.Close()

	t.Setenv("TUNNEL_PROXY_BASE_URL", proxy.URL)
	registry := NewTunnelRegistry()
	tunnel, ok := registry.Get("device-123")
	if !ok || tunnel == nil {
		t.Fatal("expected lazy device tunnel registration")
	}

	req := httptest.NewRequest(http.MethodGet, tunnel.BaseURL+"/live.m3u8", nil)
	resp, err := tunnel.client.Do(req)
	if err != nil {
		t.Fatalf("tunnel request failed: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("unexpected status: %d", resp.StatusCode)
	}
	if gotPath != "/device/device-123/live.m3u8" {
		t.Fatalf("unexpected proxy path: %q", gotPath)
	}
}

func TestTunnelRegistryRejectsUnsafeProxyConfiguration(t *testing.T) {
	registry := NewTunnelRegistry()
	for _, raw := range []string{
		"https://gateway.example",
		"http://gateway.example/proxy",
		"http://gateway.example/?x=1",
		"http://gateway.example/#fragment",
	} {
		if err := registry.RegisterFromProxy("device-123", raw); err == nil {
			t.Fatalf("expected proxy URL to be rejected: %q", raw)
		}
	}
}

func TestTunnelRegistryRejectsUnsafeDeviceIdentity(t *testing.T) {
	registry := NewTunnelRegistry()
	proxy := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer proxy.Close()
	for _, id := range []string{"", "../device", "device/123", "device..123"} {
		if _, ok := registry.Get(id); ok {
			t.Fatalf("unsafe device identity accepted: %q", id)
		}
		if err := registry.RegisterFromProxy(id, proxy.URL); err == nil {
			t.Fatalf("unsafe device identity registered: %q", id)
		}
	}
	_ = os.Getenv("PATH")
	_ = strings.TrimSpace("")
}
