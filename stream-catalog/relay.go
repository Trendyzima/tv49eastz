package main

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

const (
	maxPlaylistBytes = 8 << 20
	maxRedirects     = 3
)

var hlsURI = regexp.MustCompile(`URI="([^"]+)"`)

// relayClient deliberately rejects redirects and private/link-local destinations.
// Catalog URLs are untrusted input even though they originate from an external M3U.
func relayClient(timeout time.Duration) *http.Client {
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(address)
			if err != nil {
				return nil, err
			}
			ips, err := net.DefaultResolver.LookupIP(ctx, "ip", host)
			if err != nil {
				return nil, err
			}
			for _, ip := range ips {
				if !isPublicIP(ip) {
					continue
				}
				d := net.Dialer{Timeout: 8 * time.Second}
				conn, err := d.DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
				if err == nil {
					return conn, nil
				}
			}
			return nil, errors.New("upstream resolves only to non-public addresses")
		},
	}
	return &http.Client{
		Timeout:   timeout,
		Transport: transport,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= maxRedirects || req.URL.Scheme != "https" {
				return errors.New("relay redirect rejected")
			}
			return nil
		},
	}
}

func isPublicIP(ip net.IP) bool {
	if ip == nil || ip.IsLoopback() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() {
		return false
	}
	if ip4 := ip.To4(); ip4 != nil {
		return !(ip4[0] == 10 || ip4[0] == 127 || (ip4[0] == 172 && ip4[1] >= 16 && ip4[1] <= 31) || (ip4[0] == 192 && ip4[1] == 168) || (ip4[0] == 169 && ip4[1] == 254))
	}
	return !(ip[0]&0xfe == 0xfc || ip.Equal(net.IPv6loopback))
}

func encodeRelayURL(raw string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(raw))
}

func decodeRelayURL(raw string) (string, bool) {
	b, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil {
		return "", false
	}
	return string(b), true
}

func (s *server) relay(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	id := strings.TrimSpace(r.URL.Query().Get("id"))
	if id == "" {
		http.Error(w, "missing channel id", http.StatusBadRequest)
		return
	}
	ch, ok := s.findChannel(id)
	if !ok || !ch.Relay {
		http.Error(w, "channel is not relayable", http.StatusNotFound)
		return
	}
	s.serveUpstream(w, r, ch, ch.Stream, true)
}

func (s *server) relayAsset(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	id := strings.TrimSpace(r.URL.Query().Get("id"))
	u, ok := decodeRelayURL(strings.TrimSpace(r.URL.Query().Get("u")))
	if id == "" || !ok {
		http.Error(w, "invalid relay asset", http.StatusBadRequest)
		return
	}
	ch, ok := s.findChannel(id)
	if !ok || !ch.Relay {
		http.Error(w, "channel is not relayable", http.StatusNotFound)
		return
	}
	resolved, err := url.Parse(u)
	if err != nil || resolved.Scheme != "https" || resolved.Host == "" {
		http.Error(w, "relay asset must be HTTPS", http.StatusBadRequest)
		return
	}
	s.serveUpstream(w, r, ch, resolved.String(), false)
}

func (s *server) serveUpstream(w http.ResponseWriter, r *http.Request, ch Channel, raw string, rewrite bool) {
	ctx, cancel := context.WithTimeout(r.Context(), s.timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, r.Method, raw, nil)
	if err != nil || req.URL.Scheme != "https" || req.URL.Host == "" {
		http.Error(w, "invalid upstream URL", http.StatusBadGateway)
		return
	}
	req.Header.Set("User-Agent", "TV49East-Relay/1.0")
	resp, err := relayClient(s.timeout).Do(req)
	if err != nil {
		http.Error(w, "upstream unavailable", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		http.Error(w, fmt.Sprintf("upstream returned HTTP %d", resp.StatusCode), http.StatusBadGateway)
		return
	}

	contentType := resp.Header.Get("Content-Type")
	isPlaylist := rewrite && (strings.Contains(strings.ToLower(contentType), "mpegurl") || strings.Contains(strings.ToLower(contentType), "m3u8") || strings.HasSuffix(strings.ToLower(req.URL.Path), ".m3u8"))
	if !isPlaylist {
		copyRelayHeaders(w, resp)
		w.WriteHeader(resp.StatusCode)
		if r.Method != http.MethodHead {
			_, _ = io.Copy(w, resp.Body)
		}
		return
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxPlaylistBytes+1))
	if err != nil || int64(len(body)) > maxPlaylistBytes {
		http.Error(w, "playlist too large", http.StatusBadGateway)
		return
	}
	playlist := rewriteHLS(string(body), req.URL, ch.ID)
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = io.WriteString(w, playlist)
	}
}

func rewriteHLS(playlist string, base *url.URL, channelID string) string {
	rewriteURI := func(raw string) string {
		target, err := url.Parse(strings.TrimSpace(raw))
		if err != nil {
			return raw
		}
		target = base.ResolveReference(target)
		if target.Scheme != "https" || target.Host == "" {
			return raw
		}
		return "/v1/relay-asset?id=" + url.QueryEscape(channelID) + "&u=" + url.QueryEscape(encodeRelayURL(target.String()))
	}
	playlist = hlsURI.ReplaceAllStringFunc(playlist, func(match string) string {
		parts := hlsURI.FindStringSubmatch(match)
		if len(parts) != 2 {
			return match
		}
		return strings.Replace(match, parts[1], rewriteURI(parts[1]), 1)
	})
	lines := strings.Split(playlist, "\n")
	for i, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}
		lines[i] = rewriteURI(trimmed)
	}
	return strings.Join(lines, "\n")
}

func copyRelayHeaders(w http.ResponseWriter, resp *http.Response) {
	for _, key := range []string{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"} {
		if value := resp.Header.Get(key); value != "" {
			w.Header().Set(key, value)
		}
	}
	w.Header().Set("Cache-Control", "no-store")
}
