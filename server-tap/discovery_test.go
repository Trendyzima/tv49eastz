package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
)

func TestProbeFadCamAcceptsFragmentedMP4Playlist(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/live.m3u8" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		_, _ = w.Write([]byte("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MAP:URI=\"/init.mp4\"\nsegment-1.m4s\n"))
	}))
	defer srv.Close()

	u, err := url.Parse(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	client := srv.Client()
	if !probeFadCam(context.Background(), client, u) {
		t.Fatal("expected valid FadCam playlist to be discovered")
	}
}

func TestProbeFadCamRejectsGenericHTTPService(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("hello world"))
	}))
	defer srv.Close()

	u, err := url.Parse(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	if probeFadCam(context.Background(), srv.Client(), u) {
		t.Fatal("generic HTTP service must not be accepted as FadCam")
	}
}

func TestDiscoveryCandidatesAlwaysIncludeLoopback(t *testing.T) {
	candidates, err := discoveryCandidates(8080, 512)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, candidate := range candidates {
		if candidate == "127.0.0.1:8080" {
			found = true
			break
		}
	}
	if !found {
		t.Fatal("expected loopback FadCam candidate")
	}
}
