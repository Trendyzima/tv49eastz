package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func testShield(origin string) *shield {
	return newShield(Config{
		TunnelURL:           origin,
		CacheBytes:          1 << 20,
		MaxObjectBytes:      1 << 20,
		SegmentTTL:          time.Minute,
		OriginTimeout:       time.Second,
		MaxOriginConcurrent: 4,
		TargetTTL:           time.Minute,
	})
}

func TestSingleflightOneOriginFetch(t *testing.T) {
	var fetches atomic.Int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fetches.Add(1)
		time.Sleep(20 * time.Millisecond)
		w.Header().Set("Content-Type", "video/iso.segment")
		_, _ = w.Write([]byte("segment-data"))
	}))
	defer srv.Close()

	s := testShield(srv.URL)
	s.targets["news"] = originTarget{
		ChannelID:  "news",
		DeviceID:   "device-1",
		StreamPath: "/live.m3u8",
		Source:     "fadcam",
	}
	s.targetExp["news"] = time.Now().Add(time.Minute)

	const viewers = 100
	var wg sync.WaitGroup
	for i := 0; i < viewers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			e, err := s.singleflight(context.Background(), "news\n/seg-001.m4s", "news", "/seg-001.m4s")
			if err != nil || string(e.body) != "segment-data" {
				t.Errorf("fetch failed: %v", err)
			}
		}()
	}
	wg.Wait()

	if got := fetches.Load(); got != 1 {
		t.Fatalf("origin fetches = %d, want 1", got)
	}
}

func TestChannelPathRejectsTraversal(t *testing.T) {
	bad := []string{
		"/channel/a/../secret.m4s",
		"/channel/a//secret.m4s",
		"/channel/../live.m3u8",
		"/channel/a/secret.jpg",
	}
	for _, p := range bad {
		if id, asset, ok := parseChannelPath(p); ok && validAsset(asset) {
			t.Fatalf("accepted unsafe path %q as %q/%q", p, id, asset)
		}
	}
}

func TestRewritePlaylistUsesShieldPaths(t *testing.T) {
	in := "#EXTM3U\n#EXT-X-MAP:URI=\"/init.mp4\"\n#EXTINF:2,\nseg-001.m4s\n"
	out := string(rewritePlaylist([]byte(in), "news"))
	if !strings.Contains(out, "/channel/news/init.mp4") || !strings.Contains(out, "/channel/news/seg-001.m4s") {
		t.Fatalf("playlist was not fully rewritten: %s", out)
	}
}
