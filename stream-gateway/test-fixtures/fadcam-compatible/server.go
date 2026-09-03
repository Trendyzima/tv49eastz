package main

import (
	"log"
	"net/http"
	"os"
)

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("/live.m3u8", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write([]byte("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:1.0,\nsegment-1.m4s\n"))
	})
	mux.HandleFunc("/init.mp4", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/mp4")
		_, _ = w.Write([]byte("FADCAM-INIT-FIXTURE"))
	})
	mux.HandleFunc("/segment-1.m4s", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/iso.segment")
		_, _ = w.Write([]byte("FADCAM-MEDIA-FIXTURE"))
	})
	mux.HandleFunc("/status", func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "private control endpoint", http.StatusForbidden)
	})
	mux.HandleFunc("/audio/volume", func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "private control endpoint", http.StatusForbidden)
	})
	addr := ":8080"
	if v := os.Getenv("FADCAM_FIXTURE_ADDR"); v != "" {
		addr = v
	}
	log.Printf("FadCam-compatible fixture listening on %s", addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}
