package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func testServer(t *testing.T, handler http.Handler) (*Server, *httptest.Server) {
	t.Helper()
	upstream := httptest.NewServer(handler)
	u, err := url.Parse(upstream.URL)
	if err != nil {
		t.Fatal(err)
	}
	s := &Server{
		cfg: Config{
			Upstream:          u,
			Timeout:           2 * time.Second,
			MaxPlaylistBytes:  1 << 20,
			MaxProxyBodyBytes: 1 << 20,
		},
		client: upstream.Client(),
	}
	return s, upstream
}

func TestReadOnlyRejectsMutation(t *testing.T) {
	s, upstream := testServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Fatalf("upstream should not receive mutation: %s", r.Method)
	}))
	defer upstream.Close()

	r := httptest.NewRequest(http.MethodPost, "/status", nil)
	w := httptest.NewRecorder()
	s.status(w, r)
	if w.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusMethodNotAllowed)
	}
}

func TestPlaylistRewritesSegmentsAndInit(t *testing.T) {
	var requested []string
	s, upstream := testServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requested = append(requested, r.URL.String())
		if r.URL.Path == "/live.m3u8" {
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("#EXTM3U\n#EXT-X-MAP:URI=\"/init.mp4\"\n#EXTINF:2.0,\nsegment-1.m4s\n"))
			return
		}
		http.NotFound(w, r)
	}))
	defer upstream.Close()

	w := httptest.NewRecorder()
	s.playlist(w, httptest.NewRequest(http.MethodGet, "/live.m3u8", nil))
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	body := w.Body.String()
	if !strings.Contains(body, "/hls/") {
		t.Fatalf("playlist was not rewritten: %s", body)
	}
	if strings.Contains(body, upstream.URL) {
		t.Fatalf("upstream origin leaked into playlist: %s", body)
	}
	if len(requested) != 1 || requested[0] != "/live.m3u8" {
		t.Fatalf("unexpected upstream requests: %#v", requested)
	}
}

func TestPlaylistRejectsExternalURI(t *testing.T) {
	s, upstream := testServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("#EXTM3U\n#EXT-X-MAP:URI=\"http://example.com/init.mp4\"\n"))
	}))
	defer upstream.Close()

	w := httptest.NewRecorder()
	s.playlist(w, httptest.NewRequest(http.MethodGet, "/live.m3u8", nil))
	if w.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusBadGateway)
	}
}

func TestHLSResourceRejectsOtherOrigin(t *testing.T) {
	s, upstream := testServer(t, http.NotFoundHandler())
	defer upstream.Close()

	other, _ := url.Parse("http://evil.example/init.mp4")
	encoded := base64URL([]byte(other.String()))
	w := httptest.NewRecorder()
	s.hlsResource(w, httptest.NewRequest(http.MethodGet, "/hls/"+encoded, nil))
	if w.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want %d", w.Code, http.StatusForbidden)
	}
}

func base64URL(b []byte) string {
	const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
	var out strings.Builder
	for i := 0; i < len(b); i += 3 {
		var v uint32 = uint32(b[i]) << 16
		if i+1 < len(b) {
			v |= uint32(b[i+1]) << 8
		}
		if i+2 < len(b) {
			v |= uint32(b[i+2])
		}
		out.WriteByte(alphabet[(v>>18)&63])
		out.WriteByte(alphabet[(v>>12)&63])
		if i+1 < len(b) {
			out.WriteByte(alphabet[(v>>6)&63])
		}
		if i+2 < len(b) {
			out.WriteByte(alphabet[v&63])
		}
	}
	return out.String()
}
