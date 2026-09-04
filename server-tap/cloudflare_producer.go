package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Opt-in bridge: the existing read-only tap remains unchanged unless the
// Cloudflare producer variables are explicitly configured.
func init() {
	if strings.EqualFold(strings.TrimSpace(os.Getenv("CLOUDFLARE_PRODUCER_ENABLED")), "true") {
		go runCloudflareProducer()
	}
}

const (
	producerChunkSize = 32 * 1024
	producerMaxPath   = 512
)

type relayCommand struct {
	Type   string `json:"type"`
	ID     int    `json:"id"`
	Path   string `json:"path"`
	Method string `json:"method"`
}

type relayResponse struct {
	Type    string            `json:"type"`
	ID      int               `json:"id"`
	Status  int               `json:"status"`
	Headers map[string]string `json:"headers"`
}

type producerSocket struct {
	conn *websocket.Conn
	mu   sync.Mutex
}

func (s *producerSocket) writeJSON(value any) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.conn.WriteJSON(value)
}

func (s *producerSocket) writeBinary(value []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.conn.WriteMessage(websocket.BinaryMessage, value)
}

func runCloudflareProducer() {
	relayBase := strings.TrimRight(strings.TrimSpace(os.Getenv("CLOUDFLARE_RELAY_URL")), "/")
	streamID := strings.TrimSpace(os.Getenv("CLOUDFLARE_STREAM_ID"))
	secret := strings.TrimSpace(os.Getenv("RELAY_DEVICE_SECRET"))
	if relayBase == "" || streamID == "" || secret == "" {
		log.Printf("cloudflare producer disabled: CLOUDFLARE_RELAY_URL, CLOUDFLARE_STREAM_ID and RELAY_DEVICE_SECRET are required")
		return
	}
	if !validProducerID(streamID) {
		log.Printf("cloudflare producer disabled: invalid stream id")
		return
	}

	delay := time.Second
	for {
		err := cloudflareProducerOnce(relayBase, streamID, secret)
		if err != nil {
			log.Printf("cloudflare producer disconnected: %v; retrying in %s", err, delay)
		}
		time.Sleep(delay)
		if delay < 30*time.Second {
			delay *= 2
			if delay > 30*time.Second {
				delay = 30 * time.Second
			}
		}
	}
}

func cloudflareProducerOnce(relayBase, streamID, secret string) error {
	ticket := makeDeviceTicket(secret, streamID, 15*time.Minute)
	wsURL, err := websocketURL(relayBase, streamID, ticket)
	if err != nil {
		return err
	}

	dialer := websocket.Dialer{HandshakeTimeout: 15 * time.Second}
	conn, _, err := dialer.Dial(wsURL, nil)
	if err != nil {
		return err
	}
	defer conn.Close()

	socket := &producerSocket{conn: conn}
	_ = conn.SetReadDeadline(time.Now().Add(45 * time.Second))
	if err := socket.writeJSON(map[string]any{"type": "hello", "protocol": 2}); err != nil {
		return err
	}

	for {
		messageType, data, err := conn.ReadMessage()
		if err != nil {
			return err
		}
		_ = conn.SetReadDeadline(time.Now().Add(45 * time.Second))
		if messageType != websocket.TextMessage {
			continue
		}
		var command relayCommand
		if err := json.Unmarshal(data, &command); err != nil {
			_ = socket.writeJSON(map[string]any{"type": "error", "error": "invalid_json"})
			continue
		}
		if command.Type != "request" {
			continue
		}
		if command.ID < 0 || len(command.Path) > producerMaxPath || !allowedProducerPath(command.Path) {
			_ = socket.writeJSON(map[string]any{"type": "error", "id": command.ID, "error": "path_not_allowed"})
			continue
		}
		if command.Method != "" && command.Method != http.MethodGet {
			_ = socket.writeJSON(map[string]any{"type": "error", "id": command.ID, "error": "method_not_allowed"})
			continue
		}
		go func(c relayCommand) {
			if err := serveCloudflareRequest(socket, c); err != nil {
				_ = socket.writeJSON(map[string]any{"type": "error", "id": c.ID, "error": safeProducerError(err)})
			}
		}(command)
	}
}

func serveCloudflareRequest(socket *producerSocket, command relayCommand) error {
	localPath := command.Path
	if strings.HasPrefix(localPath, "/hls/") {
		name := strings.TrimPrefix(localPath, "/hls/")
		if !validSegmentName(name) {
			return errors.New("invalid HLS resource")
		}
		localPath = "/" + name + ".m4s"
	}
	if !allowedLocalProducerPath(localPath) {
		return errors.New("invalid local path")
	}

	upstream := strings.TrimRight(strings.TrimSpace(os.Getenv("TAP_UPSTREAM")), "/")
	if upstream == "" {
		u, err := discoverFadCamUpstream(context.Background())
		if err != nil {
			return err
		}
		upstream = strings.TrimRight(u.String(), "/")
	}
	target, err := url.Parse(upstream + localPath)
	if err != nil || target.User != nil || target.Fragment != "" || target.RawQuery != "" {
		return errors.New("invalid upstream")
	}

	client := &http.Client{Timeout: 20 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}}
	request, err := http.NewRequest(http.MethodGet, target.String(), nil)
	if err != nil {
		return err
	}
	request.Header.Set("Accept", "application/vnd.apple.mpegurl,video/mp4,video/iso.segment,application/octet-stream,*/*;q=0.5")
	request.Header.Set("Cache-Control", "no-cache")
	response, err := client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()

	headers := map[string]string{}
	for _, name := range []string{"Content-Type", "Cache-Control", "Content-Length", "ETag", "Last-Modified"} {
		if value := response.Header.Get(name); value != "" && len(value) <= 1024 {
			headers[name] = value
		}
	}
	if err := socket.writeJSON(relayResponse{Type: "response", ID: command.ID, Status: response.StatusCode, Headers: headers}); err != nil {
		return err
	}

	buffer := make([]byte, producerChunkSize)
	for {
		n, readErr := response.Body.Read(buffer)
		if n > 0 {
			frame := make([]byte, 4+n)
			binary.BigEndian.PutUint32(frame[:4], uint32(command.ID))
			copy(frame[4:], buffer[:n])
			if err := socket.writeBinary(frame); err != nil {
				return err
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return readErr
		}
	}
	return socket.writeJSON(map[string]any{"type": "end", "id": command.ID})
}

func allowedProducerPath(p string) bool {
	return p == "/live.m3u8" || p == "/init.mp4" || p == "/status" || p == "/audio/volume" || (strings.HasPrefix(p, "/hls/") && validSegmentName(strings.TrimPrefix(p, "/hls/")))
}

func allowedLocalProducerPath(p string) bool {
	return p == "/live.m3u8" || p == "/init.mp4" || p == "/status" || p == "/audio/volume" || (strings.HasPrefix(p, "/seg-") && validSegmentPath(p))
}

func validSegmentName(name string) bool {
	if len(name) < 5 || len(name) > 128 || !strings.HasPrefix(name, "seg-") {
		return false
	}
	for _, r := range name[4:] {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func validSegmentPath(p string) bool {
	return strings.HasSuffix(p, ".m4s") && validSegmentName(strings.TrimSuffix(strings.TrimPrefix(p, "/"), ".m4s"))
}

func validProducerID(value string) bool {
	if value == "" || len(value) > 128 || strings.Contains(value, "/") || strings.Contains(value, "..") {
		return false
	}
	for _, r := range value {
		if !(r == '-' || r == '_' || r == '.' || r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9') {
			return false
		}
	}
	return true
}

func makeDeviceTicket(secret, stream string, ttl time.Duration) string {
	exp := time.Now().Add(ttl).Unix()
	payload := fmt.Sprintf("device\x00%s\x00%d", stream, exp)
	encoded := base64.RawURLEncoding.EncodeToString([]byte(payload))
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(payload))
	signature := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	return encoded + "." + signature
}

func websocketURL(rawBase, stream, ticket string) (string, error) {
	u, err := url.Parse(rawBase)
	if err != nil || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return "", errors.New("invalid Cloudflare relay URL")
	}
	switch u.Scheme {
	case "https":
		u.Scheme = "wss"
	case "http":
		u.Scheme = "ws"
	default:
		return "", errors.New("Cloudflare relay URL must use http or https")
	}
	u.Path = "/tunnel"
	q := u.Query()
	q.Set("stream", stream)
	q.Set("ticket", ticket)
	u.RawQuery = q.Encode()
	return u.String(), nil
}

func safeProducerError(err error) string {
	if err == nil {
		return "producer_error"
	}
	name := err.Error()
	if len(name) > 96 {
		name = name[:96]
	}
	return name
}
