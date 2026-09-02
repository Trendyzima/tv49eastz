package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestRewritePlaylistKeepsSameOriginAndRelativeMedia(t *testing.T) {
	origin, _ := url.Parse("https://origin.example")
	input := "#EXTM3U\n#EXT-X-MAP:URI=\"/init.mp4\"\n#EXTINF:2,\nsegment-1.m4s\n"
	got, err := rewritePlaylist([]byte(input), origin)
	if err != nil { t.Fatal(err) }
	if !strings.Contains(string(got), "segment-1.m4s") { t.Fatalf("rewritten playlist lost segment: %s", got) }
	if !strings.Contains(string(got), "/init.mp4") { t.Fatalf("rewritten playlist lost map URI: %s", got) }
}

func TestRewritePlaylistRejectsForeignOrigin(t *testing.T) {
	origin, _ := url.Parse("https://origin.example")
	input := "#EXTM3U\nhttps://evil.example/segment.m4s\n"
	if _, err := rewritePlaylist([]byte(input), origin); err == nil { t.Fatal("expected foreign origin rejection") }
}

func TestEdgeCachesSegmentAndCollapsesConcurrentMisses(t *testing.T) {
	var originHits atomic.Int64
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		originHits.Add(1)
		w.Header().Set("Content-Type", "video/iso.segment")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("segment-data"))
	}))
	defer origin.Close()
	u, _ := url.Parse(origin.URL)
	e := &Edge{cfg: Config{Origin: u, MaxCacheBytes: 1 << 20, MaxObjectBytes: 1 << 20, SegmentTTL: time.Minute, OriginTimeout: time.Second}, cache: newLRU(1 << 20), client: origin.Client()}

	r1 := httptest.NewRequest(http.MethodGet, "/video-1.m4s", nil)
	w1 := httptest.NewRecorder()
	e.media(w1, r1)
	if w1.Code != http.StatusOK || w1.Body.String() != "segment-data" { t.Fatalf("first response: %d %q", w1.Code, w1.Body.String()) }

	r2 := httptest.NewRequest(http.MethodGet, "/video-1.m4s", nil)
	w2 := httptest.NewRecorder()
	e.media(w2, r2)
	if w2.Code != http.StatusOK || w2.Header().Get("X-CDN-Edge-Cache") != "HIT" { t.Fatalf("cache response: %d %q", w2.Code, w2.Header().Get("X-CDN-Edge-Cache")) }
	if originHits.Load() != 1 { t.Fatalf("expected one origin request, got %d", originHits.Load()) }
}

func TestAllowedMediaPath(t *testing.T) {
	for _, p := range []string{"/a.m4s", "/a.mp4", "/a.ts", "/a.m3u8"} {
		if !allowedMediaPath(p) { t.Errorf("expected allowed: %s", p) }
	}
	for _, p := range []string{"/../secret.m4s", "/foo/bar", "/proxy/http://evil"} {
		if allowedMediaPath(p) { t.Errorf("expected rejected: %s", p) }
	}
}
