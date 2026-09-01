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
	if ch.Stream != "https://example.com/live/demo.m3u8" || ch.Relay {
		t.Fatalf("public catalog entry must not be implicitly relayable: %+v", ch)
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

func TestParseExtInfHandlesEscapedQuotes(t *testing.T) {
	line := `#EXTINF:-1 tvg-id="east-3" tvg-name="East \"News\"" group-title="News",East News`
	meta := parseExtInf(line)
	if meta["tvg-id"] != "east-3" {
		t.Fatalf("tvg-id = %q, want east-3", meta["tvg-id"])
	}
	if meta["tvg-name"] != `East "News"` {
		t.Fatalf("tvg-name = %q, want East %q", meta["tvg-name"], `"News"`)
	}
	if meta["group-title"] != "News" || meta["name"] != "East News" {
		t.Fatalf("unexpected metadata: %+v", meta)
	}
}

func TestParseExtInfPrefersTVGMetadataAndAliases(t *testing.T) {
	line := `#EXTINF:-1 id="legacy-id" tvg-id="canonical-id" name="Legacy Name" tvg-name="Canonical Name" group="Sports" country="KE" language="en" logo="https://example.com/logo.png",Display Name`
	meta := parseExtInf(line)
	c, err := parseM3U(strings.NewReader(line+"\nhttps://example.com/live.m3u8\n"), 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	if meta["id"] != "legacy-id" || meta["tvg-id"] != "canonical-id" {
		t.Fatalf("attributes not preserved: %+v", meta)
	}
	if len(c.Channels) != 1 {
		t.Fatalf("got %d channels, want 1", len(c.Channels))
	}
	ch := c.Channels[0]
	if ch.ID != "canonical-id" || ch.Name != "Canonical Name" || ch.Group != "Sports" || ch.Country != "KE" || ch.Language != "en" || ch.Logo != "https://example.com/logo.png" || ch.Relay {
		t.Fatalf("unexpected channel metadata: %+v", ch)
	}
}

func TestSafeHTTPSStreamRejectsUnsafeSchemes(t *testing.T) {
	for _, raw := range []string{"http://example.com/a.m3u8", "file:///tmp/a", "//example.com/a", "/a.m3u8", "javascript:alert(1)"} {
		if _, ok := safeHTTPSStream(raw); ok {
			t.Fatalf("accepted unsafe stream %q", raw)
		}
	}
}
