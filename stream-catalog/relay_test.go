package main

import (
	"net"
	"net/url"
	"strings"
	"testing"
)

func TestRewriteHLSRewritesSegmentsAndKeys(t *testing.T) {
	base, err := url.Parse("https://cdn.example.test/live/master.m3u8")
	if err != nil {
		t.Fatal(err)
	}
	input := "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4,\nsegment-1.ts\n"
	out := rewriteHLS(input, base, "demo")
	if strings.Contains(out, "segment-1.ts") || strings.Contains(out, "URI=\"key.bin\"") {
		t.Fatalf("HLS URLs were not rewritten: %s", out)
	}
	if !strings.Contains(out, "/v1/relay-asset?id=demo") {
		t.Fatalf("relay asset endpoint missing: %s", out)
	}
}

func TestIsPublicIPRejectsPrivateRanges(t *testing.T) {
	for _, raw := range []string{"127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.1.1", "fc00::1", "::1"} {
		if isPublicIP(net.ParseIP(raw)) {
			t.Fatalf("accepted private IP %s", raw)
		}
	}
	if !isPublicIP(net.ParseIP("8.8.8.8")) {
		t.Fatal("rejected known public IP")
	}
}

func TestRelayURLRoundTrip(t *testing.T) {
	want := "https://cdn.example.test/live/seg.ts?token=a%2Bb"
	got, ok := decodeRelayURL(encodeRelayURL(want))
	if !ok || got != want {
		t.Fatalf("round trip failed: %q %v", got, ok)
	}
}
