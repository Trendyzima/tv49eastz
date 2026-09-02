package main

import (
	"strings"
	"testing"
)

func TestValidFadCamPath(t *testing.T) {
	valid := []string{"/live.m3u8", "/hls/segment-1.m4s", "/audio/prog.m3u8"}
	for _, path := range valid { if !validFadCamPath(path) { t.Fatalf("rejected valid FadCam path %q", path) } }
	invalid := []string{"http://127.0.0.1/live.m3u8", "//evil/live.m3u8", "/../secret", "/status", "/control"}
	for _, path := range invalid { if validFadCamPath(path) { t.Fatalf("accepted invalid FadCam path %q", path) } }
}

func TestRewriteFadCamHLSSignsLocalResources(t *testing.T) {
	input := "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"keys/key.bin\"\n#EXTINF:2,\n/hls/segment-1.m4s\n"
	out := rewriteFadCamHLS(input, "creator-1", "secret")
	if strings.Contains(out, "/hls/segment-1.m4s\n") || strings.Contains(out, "URI=\"keys/key.bin\"") { t.Fatalf("local HLS resources were not rewritten: %s", out) }
	if !strings.Contains(out, "/v1/relay-asset?id=creator-1") || !strings.Contains(out, "sig=") || !strings.Contains(out, "exp=") { t.Fatalf("signed relay capability missing: %s", out) }
}

func TestCreatorChannelRequiresTunnelOrigin(t *testing.T) {
	bad := CreatorChannel{ID:"c1", Name:"Creator", Source:"fadcam", StreamPath:"/live.m3u8"}
	if err := validateCreatorChannel(bad); err == nil { t.Fatal("accepted FadCam channel without device_id") }
	good := bad; good.DeviceID = "device-1"
	if err := validateCreatorChannel(good); err != nil { t.Fatalf("rejected valid FadCam channel: %v", err) }
}
