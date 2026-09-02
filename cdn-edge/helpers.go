package main

import (
	"errors"
	"net/http"
	"net/url"
	"os"
	"path"
	"strconv"
	"strings"
)

func writeCached(w http.ResponseWriter, entry cacheEntry) {
	contentPath := entry.key
	if u, err := url.Parse(entry.key); err == nil {
		contentPath = u.Path
	}
	w.Header().Set("Content-Type", mediaContentType(entry.contentType, contentPath))
	w.Header().Set("Cache-Control", "public, max-age=30, immutable")
	w.Header().Set("X-CDN-Edge-Cache", "HIT")
	w.Header().Set("Content-Length", strconv.Itoa(len(entry.body)))
	_, _ = w.Write(entry.body)
}

func playlistContentType(ct string) string {
	if ct != "" {
		return ct
	}
	return "application/vnd.apple.mpegurl"
}

func mediaContentType(ct, p string) string {
	if ct != "" {
		return ct
	}
	lower := strings.ToLower(p)
	if u, err := url.Parse(p); err == nil && u.Path != "" {
		lower = strings.ToLower(u.Path)
	}
	switch {
	case strings.HasSuffix(lower, ".m3u8"):
		return "application/vnd.apple.mpegurl"
	case strings.HasSuffix(lower, ".m4s"):
		return "video/iso.segment"
	case strings.HasSuffix(lower, ".mp4"):
		return "video/mp4"
	case strings.HasSuffix(lower, ".ts"):
		return "video/mp2t"
	default:
		return "application/octet-stream"
	}
}

func joinPath(base, p string) string {
	return path.Join("/", strings.Trim(base, "/"), strings.Trim(p, "/"))
}

func positiveInt64(s string) (int64, error) {
	n, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64)
	if err != nil || n <= 0 {
		return 0, errors.New("invalid positive integer")
	}
	return n, nil
}

func getenv(k, d string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return d
}
