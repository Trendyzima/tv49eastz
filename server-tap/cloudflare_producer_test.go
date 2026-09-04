package main

import (
	"strings"
	"testing"
	"time"
)

func TestMakeDeviceTicketShape(t *testing.T) {
	ticket := makeDeviceTicket("test-secret", "device-01", time.Minute)
	parts := strings.Split(ticket, ".")
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		t.Fatalf("invalid device ticket shape: %q", ticket)
	}
}

func TestWebsocketURL(t *testing.T) {
	got, err := websocketURL("https://example.workers.dev", "device-01", "ticket")
	if err != nil {
		t.Fatal(err)
	}
	want := "wss://example.workers.dev/tunnel?stream=device-01&ticket=ticket"
	if got != want {
		t.Fatalf("websocket URL = %q, want %q", got, want)
	}
}

func TestProducerPathPolicy(t *testing.T) {
	allowed := []string{"/live.m3u8", "/init.mp4", "/status", "/audio/volume", "/hls/seg-1"}
	for _, path := range allowed {
		if !allowedProducerPath(path) {
			t.Errorf("expected path to be allowed: %s", path)
		}
	}
	blocked := []string{"/", "/auth/login", "/hls/seg-abc", "/hls/../secret", "/recording/toggle"}
	for _, path := range blocked {
		if allowedProducerPath(path) {
			t.Errorf("expected path to be blocked: %s", path)
		}
	}
}
