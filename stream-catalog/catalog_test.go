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
	if err != nil {
		t.Fatal(err)
	}
	if len(c.Channels) != 1 {
		t.Fatalf("got %d channels, want 1", len(c.Channels))
	}
	ch := c.Channels[0]
	if ch.ID != "demo" || ch.Name != "Demo News" || ch.Country != "KE" || ch.Group != "News" {
		t.Fatalf("unexpected metadata: %+v", ch)
	}
	if ch.Stream != "https://example.com/live/demo.m3u8" || !ch.Relay {
		t.Fatalf("unexpected stream: %+v", ch)
	}
}

func TestParseExtInfHandlesQuotedSpacesAndComma(t *testing.T) {
	line := `#EXTINF:-1 tvg-id="east-1" tvg-name="East News" group-title="News, Kenya" tvg-country="KE",East News, Live`
	meta := parseExtInf(line)
	if meta["tvg-id"] != "east-1" {
		t.Fatalf("tvg-id = %q, want east-1", meta["tvg-id"])
	}
	if meta["tvg-name"] != "East News" {
		t.Fatalf("tvg-name = %q, want East News", meta["tvg-name"])
	}
	if meta["group-title"] != "News, Kenya" {
		t.Fatalf("group-title = %q, want News, Kenya", meta["group-title"])
	}
	if meta["name"] != "East News, Live" {
		t.Fatalf("name = %q, want East News, Live", meta["name"])
	}
}

func TestParseExtInfPreservesMultipleDisplayNameCommas(t *testing.T) {
	line := `#EXTINF:-1 tvg-id="east-2" tvg-name="East 2" group-title="News, Kenya",East News, Live, Today`
	meta := parseExtInf(line)
	if meta["tvg-id"] != "east-2" {
		t.Fatalf("tvg-id = %q, want east-2", meta["tvg-id"])
	}
	if meta["group-title"] != "News, Kenya" {
		t.Fatalf("group-title = %q, want News, Kenya", meta["group-title"])
	}
	if meta["name"] != "East News, Live, Today" {
		t.Fatalf("name = %q, want East News, Live, Today", meta["name"])
	}
}

func TestParseExtInfHandlesDurationWithoutAttributes(t *testing.T) {
	meta := parseExtInf(`#EXTINF:-1,Plain Channel, With Commas`)
	if meta["name"] != "Plain Channel, With Commas" {
		t.Fatalf("name = %q, want Plain Channel, With Commas", meta["name"])
	}
}

func TestSafeHTTPSStreamRejectsUnsafeSchemes(t *testing.T) {
	for _, raw := range []string{"http://example.com/a.m3u8", "file:///tmp/a", "//example.com/a", "/a.m3u8", "javascript:alert(1)"} {
		if _, ok := safeHTTPSStream(raw); ok {
			t.Fatalf("accepted unsafe stream %q", raw)
		}
	}
}
