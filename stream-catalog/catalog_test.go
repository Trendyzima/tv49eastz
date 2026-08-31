package main

import (
	"strings"
	"testing"
)

func TestParseM3UFiltersToHTTPS(t *testing.T) {
	input := `#EXTM3U
#EXTINF:-1 tvg-id="demo" tvg-country="KE" group-title="News",Demo News
https://example.com/live/demo.m3u8
#EXTINF:-1 tvg-id="bad",HTTP Channel
http://example.com/live.m3u8
#EXTINF:-1,Relative
/live.m3u8
`
	c, err := parseM3U(strings.NewReader(input), 1<<20)
	if err != nil { t.Fatal(err) }
	if len(c.Channels) != 1 { t.Fatalf("got %d channels, want 1", len(c.Channels)) }
	ch := c.Channels[0]
	if ch.ID != "demo" || ch.Name != "Demo News" || ch.Country != "KE" || ch.Group != "News" { t.Fatalf("unexpected metadata: %+v", ch) }
	if ch.Stream != "https://example.com/live/demo.m3u8" || ch.Relay { t.Fatalf("unexpected stream: %+v", ch) }
}

func TestSafeHTTPSStreamRejectsUnsafeSchemes(t *testing.T) {
	for _, raw := range []string{"http://example.com/a.m3u8", "file:///tmp/a", "//example.com/a", "/a.m3u8", "javascript:alert(1)"} {
		if _, ok := safeHTTPSStream(raw); ok { t.Fatalf("accepted unsafe stream %q", raw) }
	}
}
