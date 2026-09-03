package main

import (
	"encoding/base64"
	"strings"
	"testing"
	"time"
)

func TestAllowedMediaPathRejectsTraversalAndControlTargets(t *testing.T) {
	bad := []string{"../secret", "..%2Fsecret", "%2e%2e/secret", "%252e%252e/secret", "/../secret", "/status", "/status/x", "/audio/volume", "/audio/volume/x", "//evil.example/x", "https://evil.example/x", "http://evil.example/x", "", "?device=x"}
	for _, path := range bad {
		if allowedMediaPath(path) {
			t.Errorf("accepted unsafe media path %q", path)
		}
	}
	good := []string{"/segment-1.m4s", "/video/init.mp4", "/playlist.m3u8?token=abc"}
	for _, path := range good {
		if !allowedMediaPath(path) {
			t.Errorf("rejected valid media path %q", path)
		}
	}
}

func TestCapabilityBindsSessionStreamExpiryAndPath(t *testing.T) {
	g := &Gateway{cfg: Config{CapabilityKey: "secret"}}
	expires := time.Now().UTC().Add(time.Minute).Truncate(time.Second)
	token, err := g.signCapability("s", "stream-a", "/segment-1.m4s?x=1", expires)
	if err != nil {
		t.Fatal(err)
	}
	if path, ok := g.verifyCapability(token, "s", "stream-a", expires); !ok || path != "/segment-1.m4s?x=1" {
		t.Fatalf("valid capability rejected: %q %v", path, ok)
	}
	for _, tc := range []struct {
		name, session, stream string
		exp                   time.Time
	}{
		{"wrong session", "other", "stream-a", expires}, {"wrong stream", "s", "other", expires}, {"wrong expiry", "s", "stream-a", expires.Add(time.Second)},
	} {
		if _, ok := g.verifyCapability(token, tc.session, tc.stream, tc.exp); ok {
			t.Errorf("accepted %s", tc.name)
		}
	}
}

func TestCapabilityRejectsTampering(t *testing.T) {
	g := &Gateway{cfg: Config{CapabilityKey: "secret"}}
	expires := time.Now().UTC().Add(time.Minute).Truncate(time.Second)
	token, err := g.signCapability("s", "stream-a", "/segment-1.m4s", expires)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		t.Fatal(err)
	}
	raw = []byte(strings.Replace(string(raw), "/segment-1.m4s", "/status", 1))
	tampered := base64.RawURLEncoding.EncodeToString(raw)
	if _, ok := g.verifyCapability(tampered, "s", "stream-a", expires); ok {
		t.Fatal("accepted tampered capability")
	}
}
