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
		if r.URL.Path == "/live.m3u8" { w.Header().Set("Content-Type", "application/vnd.apple.mpegurl"); io.WriteString(w, "#EXTM3U\n#EXTINF:1,\n/segment-1.m4s\n"); return }
		if r.URL.Path == "/segment-1.m4s" { w.Header().Set("Content-Type", "video/iso.segment"); io.WriteString(w, "MEDIA-BYTES"); return }
		http.NotFound(w, r)
	})); defer origin.Close()
	g := &Gateway{cfg: Config{CapabilityKey:"test-secret", Upstream:origin.URL}, client:origin.Client(), tunnels:NewTunnelRegistry()}
	g.tunnels.Register("device-a", origin.URL, origin.Client().Transport)
	s := Session{ID:"s", UserID:"u", DeviceID:"device-a", StreamID:"stream-a", IssuedAt:time.Now().UTC(), Expires:time.Now().UTC().Add(time.Minute)}
	g.sessions.Store(s.ID,s); g.sessionCount.Add(1)
	r := httptest.NewRequest("GET", "/stream/s/index.m3u8", nil); w:=httptest.NewRecorder(); g.stream(w,r)
	body:=w.Body.String(); if w.Code != http.StatusOK { t.Fatalf("playlist status=%d body=%s",w.Code,body) }
	if strings.Contains(body, origin.URL) || strings.Contains(body,"192.168.100.5:8080") { t.Fatal("private origin leaked into public playlist") }
	if strings.Contains(body,"/status") || strings.Contains(body,"/audio/volume") { t.Fatal("control endpoint leaked") }

	for _, path := range []string{"/stream/s/resource/bad", "/stream/s/resource/"} { rr:=httptest.NewRequest("GET",path,nil); ww:=httptest.NewRecorder(); g.stream(ww,rr); if ww.Code == http.StatusOK { t.Fatalf("invalid resource accepted: %s",path) } }
}
