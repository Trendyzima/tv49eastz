package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const defaultPlaylistURL = "https://iptv-org.github.io/iptv/index.m3u"

type Channel struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Group    string `json:"group,omitempty"`
	Country  string `json:"country,omitempty"`
	Language string `json:"language,omitempty"`
	Logo     string `json:"logo,omitempty"`
	Stream   string `json:"stream"`
	Source   string `json:"source"`
	Relay    bool   `json:"relay"`
}

type Catalog struct {
	Channels []Channel `json:"channels"`
	Updated  time.Time `json:"updated"`
}

func fetchCatalog(ctx context.Context, playlistURL string, timeout time.Duration, maxBytes int64) (Catalog, error) {
	if playlistURL == "" {
		playlistURL = defaultPlaylistURL
	}
	u, err := url.Parse(playlistURL)
	if err != nil || u.Scheme != "https" || u.Host == "" {
		return Catalog{}, errors.New("playlist URL must be HTTPS")
	}
	client := &http.Client{Timeout: timeout, CheckRedirect: func(req *http.Request, _ []*http.Request) error {
		if req.URL.Scheme != "https" {
			return errors.New("playlist redirect must remain HTTPS")
		}
		return nil
	}}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, playlistURL, nil)
	if err != nil {
		return Catalog{}, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return Catalog{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return Catalog{}, fmt.Errorf("playlist returned HTTP %d", resp.StatusCode)
	}
	return parseM3U(resp.Body, maxBytes)
}

func parseM3U(r interface{ Read([]byte) (int, error) }, maxBytes int64) (Catalog, error) {
	if maxBytes <= 0 {
		maxBytes = 32 << 20
	}
	s := bufio.NewScanner(r)
	s.Buffer(make([]byte, 4096), 1<<20)
	var out []Channel
	var meta map[string]string
	var total int64
	for s.Scan() {
		line := strings.TrimSpace(s.Text())
		total += int64(len(s.Bytes())) + 1
		if total > maxBytes {
			return Catalog{}, errors.New("playlist exceeds maximum size")
		}
		if line == "" || line == "#EXTM3U" {
			continue
		}
		if strings.HasPrefix(line, "#EXTINF:") {
			meta = parseExtInf(line)
			continue
		}
		if strings.HasPrefix(line, "#") || meta == nil {
			continue
		}
		stream, ok := safeHTTPSStream(line)
		if !ok {
			meta = nil
			continue
		}
		name := meta["name"]
		if name == "" {
			name = meta["tvg-name"]
		}
		if name == "" {
			name = stream
		}
		id := meta["tvg-id"]
		if id == "" {
			id = meta["id"]
		}
		if id == "" {
			id = slug(name + "|" + stream)
		}
		out = append(out, Channel{
			ID: id, Name: name, Group: meta["group-title"], Country: meta["tvg-country"],
			Language: meta["tvg-language"], Logo: meta["tvg-logo"], Stream: stream,
			Source: "iptv-org", Relay: true,
		})
		meta = nil
	}
	if err := s.Err(); err != nil {
		return Catalog{}, err
	}
	return Catalog{Channels: out, Updated: time.Now().UTC()}, nil
}

// parseExtInf parses EXTINF attributes while treating commas inside quoted
// attribute values as data. The final comma, outside quotes, separates the
// display name from the attribute section.
func parseExtInf(line string) map[string]string {
	m := map[string]string{}
	attrs := strings.TrimPrefix(line, "#EXTINF:")

	// The first token is the EXTINF duration (normally -1). Attribute parsing
	// starts after that token, while the display-name comma is handled below.
	if i := strings.IndexByte(attrs, ' '); i >= 0 {
		attrs = strings.TrimSpace(attrs[i:])
	}

	comma := -1
	quoted := false
	for i := 0; i < len(attrs); i++ {
		switch attrs[i] {
		case '"':
			quoted = !quoted
		case ',':
			if !quoted {
				comma = i
			}
		}
	}
	if comma >= 0 {
		m["name"] = strings.TrimSpace(attrs[comma+1:])
		attrs = strings.TrimSpace(attrs[:comma])
	}

	for len(attrs) > 0 {
		attrs = strings.TrimSpace(attrs)
		if attrs == "" {
			break
		}
		space := strings.IndexByte(attrs, ' ')
		eq := strings.IndexByte(attrs, '=')
		if eq < 1 || (space >= 0 && space < eq) {
			break
		}
		key := strings.TrimSpace(attrs[:eq])
		attrs = strings.TrimSpace(attrs[eq+1:])
		if attrs == "" {
			m[key] = ""
			break
		}
		if attrs[0] == '"' {
			attrs = attrs[1:]
			end := strings.IndexByte(attrs, '"')
			if end < 0 {
				m[key] = attrs
				break
			}
			m[key] = attrs[:end]
			attrs = attrs[end+1:]
			continue
		}
		end := strings.IndexByte(attrs, ' ')
		if end < 0 {
			m[key] = attrs
			break
		}
		m[key] = attrs[:end]
		attrs = attrs[end:]
	}
	return m
}

func safeHTTPSStream(raw string) (string, bool) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Scheme != "https" || u.Host == "" {
		return "", false
	}
	return u.String(), true
}

func slug(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	var b strings.Builder
	for _, r := range s {
		if r >= 'a' && r <= 'z' || r >= '0' && r <= '9' {
			b.WriteRune(r)
		} else if b.Len() > 0 {
			b.WriteByte('-')
		}
	}
	return strings.Trim(b.String(), "-")
}
