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
		if line == "" || strings.EqualFold(line, "#EXTM3U") {
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
		name := firstNonEmpty(meta["name"], meta["tvg-name"], stream)
		id := firstNonEmpty(meta["tvg-id"], meta["id"], slug(name+"|"+stream))
		out = append(out, Channel{
			ID:       id,
			Name:     name,
			Group:    firstNonEmpty(meta["group-title"], meta["group"]),
			Country:  firstNonEmpty(meta["tvg-country"], meta["country"]),
			Language: firstNonEmpty(meta["tvg-language"], meta["language"]),
			Logo:     firstNonEmpty(meta["tvg-logo"], meta["logo"]),
			Stream:   stream,
			Source:   "iptv-org",
			Relay:    true,
		})
		meta = nil
	}
	if err := s.Err(); err != nil {
		return Catalog{}, err
	}
	return Catalog{Channels: out, Updated: time.Now().UTC()}, nil
}

// parseExtInf parses an EXTINF record while keeping the duration, attributes,
// and display name distinct. Quoted attribute values may contain spaces,
// commas, and escaped quotes. The first comma outside a quoted value separates
// the attribute section from the display name.
func parseExtInf(line string) map[string]string {
	m := make(map[string]string)
	body := strings.TrimSpace(strings.TrimPrefix(line, "#EXTINF:"))
	if body == "" {
		return m
	}

	// Consume the duration token. Attributes begin after whitespace; a comma
	// immediately after the duration means there are no attributes.
	delimiter := strings.IndexAny(body, " \t,")
	if delimiter < 0 {
		return m
	}
	if body[delimiter] == ',' {
		m["name"] = strings.TrimSpace(body[delimiter+1:])
		return m
	}
	attrs := strings.TrimLeft(body[delimiter:], " \t")

	for len(attrs) > 0 {
		attrs = strings.TrimLeft(attrs, " \t")
		if attrs == "" {
			break
		}
		if attrs[0] == ',' {
			m["name"] = strings.TrimSpace(attrs[1:])
			break
		}

		eq := strings.IndexByte(attrs, '=')
		if eq <= 0 {
			break
		}
		key := strings.TrimSpace(attrs[:eq])
		if key == "" || strings.ContainsAny(key, " \t,") {
			break
		}
		attrs = strings.TrimLeft(attrs[eq+1:], " \t")
		if attrs == "" {
			m[key] = ""
			break
		}

		if attrs[0] == '"' {
			value, rest, ok := consumeQuoted(attrs)
			if !ok {
				// A malformed quoted attribute is not allowed to consume the
				// remainder as arbitrary metadata.
				break
			}
			m[key] = value
			attrs = rest
			continue
		}

		end := strings.IndexAny(attrs, " \t,")
		if end < 0 {
			m[key] = attrs
			break
		}
		m[key] = attrs[:end]
		attrs = attrs[end:]
	}
	return m
}

func consumeQuoted(s string) (string, string, bool) {
	if len(s) == 0 || s[0] != '"' {
		return "", s, false
	}
	var b strings.Builder
	for i := 1; i < len(s); i++ {
		switch s[i] {
		case '\\':
			if i+1 >= len(s) {
				return "", s, false
			}
			b.WriteByte(s[i+1])
			i++
		case '"':
			return b.String(), s[i+1:], true
		default:
			b.WriteByte(s[i])
		}
	}
	return "", s, false
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
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
