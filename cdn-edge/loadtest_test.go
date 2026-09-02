package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestEdgeCollapsesManyConcurrentMisses(t *testing.T) {
	var originHits atomic.Int64
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		originHits.Add(1)
		time.Sleep(25 * time.Millisecond)
		w.Header().Set("Content-Type", "video/iso.segment")
		_, _ = w.Write([]byte("segment"))
	}))
	defer origin.Close()

	u, _ := url.Parse(origin.URL)
	e := &Edge{cfg: Config{Origin: u, MaxCacheBytes: 1 << 20, MaxObjectBytes: 1 << 20, SegmentTTL: time.Minute, OriginTimeout: time.Second}, cache: newLRU(1 << 20), client: origin.Client()}

	const viewers = 500
	var wg sync.WaitGroup
	wg.Add(viewers)
	for i := 0; i < viewers; i++ {
		go func() {
			defer wg.Done()
			r := httptest.NewRequestWithContext(context.Background(), http.MethodGet, "/live-001.m4s", nil)
			w := httptest.NewRecorder()
			e.media(w, r)
			if w.Code != http.StatusOK {
				t.Errorf("status=%d body=%q", w.Code, w.Body.String())
			}
		}()
	}
	wg.Wait()

	if got := originHits.Load(); got != 1 {
		t.Fatalf("expected one origin fetch for %d concurrent viewers, got %d", viewers, got)
	}
}
