package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

const (
	publisherControlPath = "/v1/publisher/session"
	publisherProtocol    = 1
	publisherMaxBody     = 16 << 10
	publisherMaxLifetime = 30 * time.Second
	publisherClockSkew   = 30 * time.Second
)

type publisherControl struct {
	deviceID       string
	gatewayURL     *url.URL
	apiKey         string
	client         *http.Client
	publicKey      *ecdsa.PublicKey
	publicKeyBytes []byte
	mu             sync.Mutex
	nonces         map[string]time.Time
}

type publisherRequest struct {
	Version   int    `json:"v"`
	Nonce     string `json:"nonce"`
	IssuedAt  int64  `json:"iat"`
	DeviceID  string `json:"device_id"`
	ChannelID string `json:"channel_id"`
	StreamID  string `json:"stream_id"`
	PublicKey string `json:"pub"`
	Signature string `json:"sig"`
}

type publisherResponse struct {
	Session   string `json:"session"`
	ExpiresIn int    `json:"expires_in"`
	Playlist  string `json:"playlist"`
}

func newPublisherControl(deviceID string, tlsCfg *tls.Config) (*publisherControl, error) {
	if strings.TrimSpace(deviceID) == "" {
		return nil, errors.New("publisher device identity required")
	}
	controlRaw := strings.TrimSpace(os.Getenv("GATEWAY_CONTROL_URL"))
	if controlRaw == "" {
		return nil, errors.New("GATEWAY_CONTROL_URL is required for publisher control")
	}
	u, err := url.Parse(controlRaw)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return nil, errors.New("GATEWAY_CONTROL_URL must be an HTTPS origin")
	}
	if tlsCfg == nil {
		return nil, errors.New("publisher control requires mTLS configuration")
	}
	pubPath := strings.TrimSpace(os.Getenv("PUBLISHER_PUBLIC_KEY_FILE"))
	if pubPath == "" {
		return nil, errors.New("PUBLISHER_PUBLIC_KEY_FILE is required")
	}
	der, err := os.ReadFile(pubPath)
	if err != nil {
		return nil, fmt.Errorf("read publisher public key: %w", err)
	}
	der = bytes.TrimSpace(der)
	if len(der) > 8192 {
		return nil, errors.New("publisher public key too large")
	}
	pubAny, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, fmt.Errorf("parse publisher public key: %w", err)
	}
	pub, ok := pubAny.(*ecdsa.PublicKey)
	if !ok || pub.Curve == nil || pub.X == nil || pub.Y == nil {
		return nil, errors.New("publisher public key must be EC")
	}
	apiKey := strings.TrimSpace(os.Getenv("GATEWAY_API_KEY"))
	if apiKey == "" {
		return nil, errors.New("GATEWAY_API_KEY is required for publisher control")
	}
	transportCfg := tlsCfg.Clone()
	client := &http.Client{Timeout: 10 * time.Second, Transport: &http.Transport{TLSClientConfig: transportCfg, Proxy: nil, ForceAttemptHTTP2: true}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	return &publisherControl{deviceID: strings.TrimSpace(deviceID), gatewayURL: u, apiKey: apiKey, client: client, publicKey: pub, publicKeyBytes: der, nonces: make(map[string]time.Time)}, nil
}

func (p *publisherControl) verifyRequest(req publisherRequest) error {
	if req.Version != publisherProtocol || req.Nonce == "" || len(req.Nonce) > 128 || req.IssuedAt <= 0 || req.DeviceID != p.deviceID || req.ChannelID == "" || req.StreamID == "" || req.PublicKey == "" || req.Signature == "" {
		return errors.New("invalid publisher request")
	}
	now := time.Now()
	issued := time.Unix(req.IssuedAt, 0)
	if issued.After(now.Add(publisherClockSkew)) || now.Sub(issued) > publisherMaxLifetime {
		return errors.New("publisher request expired")
	}
	providedKey, err := base64.RawURLEncoding.DecodeString(req.PublicKey)
	if err != nil || !bytes.Equal(providedKey, p.publicKeyBytes) {
		return errors.New("publisher identity mismatch")
	}
	sig, err := base64.RawURLEncoding.DecodeString(req.Signature)
	if err != nil || len(sig) == 0 || len(sig) > 512 {
		return errors.New("invalid publisher signature")
	}
	canonical := fmt.Sprintf("%d|%s|%d|%s|%s|%s", req.Version, req.Nonce, req.IssuedAt, req.DeviceID, req.ChannelID, req.StreamID)
	digest := sha256.Sum256([]byte(canonical))
	if !ecdsa.VerifyASN1(p.publicKey, digest[:], sig) {
		return errors.New("publisher signature verification failed")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	for nonce, at := range p.nonces {
		if now.Sub(at) > publisherMaxLifetime+publisherClockSkew {
			delete(p.nonces, nonce)
		}
	}
	if _, exists := p.nonces[req.Nonce]; exists {
		return errors.New("publisher nonce replay")
	}
	p.nonces[req.Nonce] = now
	return nil
}

func (p *publisherControl) handleSession(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, publisherMaxBody+1))
	if err != nil || len(body) > publisherMaxBody {
		http.Error(w, "request too large", http.StatusRequestEntityTooLarge)
		return
	}
	var req publisherRequest
	dec := json.NewDecoder(bytes.NewReader(body))
	if err := dec.Decode(&req); err != nil || dec.Decode(&struct{}{}) != io.EOF {
		http.Error(w, "invalid JSON", http.StatusBadRequest)
		return
	}
	if err := p.verifyRequest(req); err != nil {
		http.Error(w, err.Error(), http.StatusForbidden)
		return
	}
	payload, _ := json.Marshal(map[string]string{"channel_id": req.ChannelID, "stream_id": req.StreamID})
	endpoint := *p.gatewayURL
	endpoint.Path = "/v1/session"
	endpoint.RawQuery = ""
	request, err := http.NewRequestWithContext(r.Context(), http.MethodPost, endpoint.String(), bytes.NewReader(payload))
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	request.Header.Set("Authorization", "Bearer "+p.apiKey)
	request.Header.Set("Content-Type", "application/json")
	response, err := p.client.Do(request)
	if err != nil {
		http.Error(w, "gateway unavailable", http.StatusBadGateway)
		return
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(io.LimitReader(response.Body, publisherMaxBody+1))
	if err != nil || len(responseBody) > publisherMaxBody {
		http.Error(w, "invalid gateway response", http.StatusBadGateway)
		return
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		http.Error(w, "gateway rejected publisher session", http.StatusBadGateway)
		return
	}
	var gateway publisherResponse
	if err := json.Unmarshal(responseBody, &gateway); err != nil || gateway.Session == "" || gateway.Playlist == "" {
		http.Error(w, "invalid gateway response", http.StatusBadGateway)
		return
	}
	playlist, err := p.gatewayURL.Parse(gateway.Playlist)
	if err != nil || playlist.Scheme != "https" || playlist.Host != p.gatewayURL.Host || playlist.User != nil || playlist.Fragment != "" {
		http.Error(w, "invalid gateway playlist", http.StatusBadGateway)
		return
	}
	gateway.Playlist = playlist.String()
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(gateway)
}

func (p *publisherControl) handleRevoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	id := strings.TrimPrefix(r.URL.Path, publisherControlPath+"/")
	if id == "" || strings.Contains(id, "/") || len(id) > 128 {
		http.Error(w, "invalid session", http.StatusBadRequest)
		return
	}
	endpoint := *p.gatewayURL
	endpoint.Path = "/v1/session/" + url.PathEscape(id)
	endpoint.RawQuery = ""
	request, err := http.NewRequestWithContext(r.Context(), http.MethodDelete, endpoint.String(), nil)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	request.Header.Set("Authorization", "Bearer "+p.apiKey)
	response, err := p.client.Do(request)
	if err != nil {
		http.Error(w, "gateway unavailable", http.StatusBadGateway)
		return
	}
	defer response.Body.Close()
	io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
	if response.StatusCode != http.StatusNoContent && response.StatusCode != http.StatusOK && response.StatusCode != http.StatusNotFound {
		http.Error(w, "gateway rejected revoke", http.StatusBadGateway)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (p *publisherControl) serve(addr string) error {
	mux := http.NewServeMux()
	mux.HandleFunc(publisherControlPath, p.handleSession)
	mux.HandleFunc(publisherControlPath+"/", p.handleRevoke)
	server := &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 3 * time.Second, IdleTimeout: 10 * time.Second}
	return server.ListenAndServe()
}

func publisherPublicKeyFingerprint(raw []byte) string {
	h := sha256.Sum256(raw)
	return hex.EncodeToString(h[:])
}
