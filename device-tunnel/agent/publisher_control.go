package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	publisherControlPath = "/v1/publisher/session"
	publisherEnrollPath  = "/v1/publisher/enroll"
	publisherIdentityPath = "/v1/publisher/identity"
	publisherProtocol    = 1
	publisherMaxBody     = 16 << 10
	publisherMaxLifetime = 30 * time.Second
	publisherClockSkew   = 30 * time.Second
	publisherMaxSession  = 15 * time.Minute
)

type publisherControl struct {
	deviceID       string
	gatewayURL     *url.URL
	apiKey         string
	client         *http.Client
	publicKey      *ecdsa.PublicKey
	publicKeyBytes []byte
	publicKeyPath  string
	enrollToken    string
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

type publisherEnrollRequest struct {
	Token     string `json:"token"`
	DeviceID  string `json:"device_id"`
	PublicKey string `json:"pub"`
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
	der, loadErr := os.ReadFile(pubPath)
	if loadErr == nil {
		der = bytes.TrimSpace(der)
		if len(der) > 8192 {
			return nil, errors.New("publisher public key too large")
		}
	} else if !errors.Is(loadErr, os.ErrNotExist) {
		return nil, fmt.Errorf("read publisher public key: %w", loadErr)
	} else if strings.TrimSpace(os.Getenv("PUBLISHER_ENROLL_TOKEN")) == "" {
		return nil, fmt.Errorf("read publisher public key: %w", loadErr)
	}
	var pub *ecdsa.PublicKey
	if len(der) != 0 {
		pubAny, parseErr := x509.ParsePKIXPublicKey(der)
		if parseErr != nil {
			return nil, fmt.Errorf("parse publisher public key: %w", parseErr)
		}
		var ok bool
		pub, ok = pubAny.(*ecdsa.PublicKey)
		if !ok || pub.Curve == nil || pub.X == nil || pub.Y == nil || pub.Curve.Params() == nil || pub.Curve.Params().Name != elliptic.P256().Params().Name {
			return nil, errors.New("publisher public key must be ECDSA P-256")
		}
	}
	apiKey := strings.TrimSpace(os.Getenv("GATEWAY_API_KEY"))
	if apiKey == "" {
		return nil, errors.New("GATEWAY_API_KEY is required for publisher control")
	}
	enrollToken := strings.TrimSpace(os.Getenv("PUBLISHER_ENROLL_TOKEN"))
	transportCfg := tlsCfg.Clone()
	client := &http.Client{Timeout: 10 * time.Second, Transport: &http.Transport{TLSClientConfig: transportCfg, Proxy: nil, ForceAttemptHTTP2: true}, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	return &publisherControl{deviceID: strings.TrimSpace(deviceID), gatewayURL: u, apiKey: apiKey, client: client, publicKey: pub, publicKeyBytes: der, publicKeyPath: pubPath, enrollToken: enrollToken, nonces: make(map[string]time.Time)}, nil
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
	if err != nil {
		return errors.New("publisher identity mismatch")
	}
	p.mu.Lock()
	pub := p.publicKey
	pinned := append([]byte(nil), p.publicKeyBytes...)
	p.mu.Unlock()
	if pub == nil || !bytes.Equal(providedKey, pinned) {
		return errors.New("publisher identity mismatch")
	}
	sig, err := base64.RawURLEncoding.DecodeString(req.Signature)
	if err != nil || len(sig) == 0 || len(sig) > 512 {
		return errors.New("invalid publisher signature")
	}
	canonical := fmt.Sprintf("%d|%s|%d|%s|%s|%s", req.Version, req.Nonce, req.IssuedAt, req.DeviceID, req.ChannelID, req.StreamID)
	digest := sha256.Sum256([]byte(canonical))
	if !ecdsa.VerifyASN1(pub, digest[:], sig) {
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
	if err := json.Unmarshal(responseBody, &gateway); err != nil || gateway.Session == "" || gateway.Playlist == "" || gateway.ExpiresIn <= 0 || time.Duration(gateway.ExpiresIn)*time.Second > publisherMaxSession {
		http.Error(w, "invalid gateway response", http.StatusBadGateway)
		return
	}
	playlist, err := p.gatewayURL.Parse(gateway.Playlist)
	if err != nil || playlist.Scheme != "https" || playlist.Host != p.gatewayURL.Host || playlist.User != nil || playlist.Fragment != "" || !strings.HasPrefix(playlist.Path, "/stream/") {
		http.Error(w, "invalid gateway playlist", http.StatusBadGateway)
		return
	}
	gateway.Playlist = playlist.String()
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(gateway)
}

func (p *publisherControl) handleEnroll(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if p.enrollToken == "" {
		http.Error(w, "publisher enrollment disabled", http.StatusNotFound)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, publisherMaxBody+1))
	if err != nil || len(body) > publisherMaxBody {
		http.Error(w, "request too large", http.StatusRequestEntityTooLarge)
		return
	}
	var req publisherEnrollRequest
	dec := json.NewDecoder(bytes.NewReader(body))
	if err := dec.Decode(&req); err != nil || dec.Decode(&struct{}{}) != io.EOF {
		http.Error(w, "invalid JSON", http.StatusBadRequest)
		return
	}
	if req.DeviceID != p.deviceID || req.Token == "" || !constantTime(req.Token, p.enrollToken) || req.PublicKey == "" {
		http.Error(w, "enrollment rejected", http.StatusForbidden)
		return
	}
	der, err := base64.RawURLEncoding.DecodeString(req.PublicKey)
	if err != nil || len(der) == 0 || len(der) > 8192 {
		http.Error(w, "invalid public key", http.StatusBadRequest)
		return
	}
	pubAny, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		http.Error(w, "invalid public key", http.StatusBadRequest)
		return
	}
	pub, ok := pubAny.(*ecdsa.PublicKey)
	if !ok || pub.Curve == nil || pub.X == nil || pub.Y == nil || pub.Curve.Params() == nil || pub.Curve.Params().Name != elliptic.P256().Params().Name {
		http.Error(w, "publisher public key must be ECDSA P-256", http.StatusBadRequest)
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.publicKey != nil || len(p.publicKeyBytes) != 0 {
		if bytes.Equal(der, p.publicKeyBytes) {
			w.Header().Set("Cache-Control", "no-store")
			_ = json.NewEncoder(w).Encode(map[string]string{"fingerprint": publisherPublicKeyFingerprint(der)})
			return
		}
		http.Error(w, "publisher identity already provisioned", http.StatusConflict)
		return
	}
	if err := os.MkdirAll(filepath.Dir(p.publicKeyPath), 0700); err != nil {
		http.Error(w, "unable to persist publisher identity", http.StatusInternalServerError)
		return
	}
	tmp, err := os.CreateTemp(filepath.Dir(p.publicKeyPath), ".publisher-key-*")
	if err != nil {
		http.Error(w, "unable to persist publisher identity", http.StatusInternalServerError)
		return
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)
	if err := tmp.Chmod(0600); err != nil { _ = tmp.Close(); http.Error(w, "unable to persist publisher identity", 500); return }
	if _, err := tmp.Write(der); err != nil { _ = tmp.Close(); http.Error(w, "unable to persist publisher identity", 500); return }
	if err := tmp.Close(); err != nil { http.Error(w, "unable to persist publisher identity", 500); return }
	if err := os.Rename(tmpName, p.publicKeyPath); err != nil { http.Error(w, "unable to persist publisher identity", 500); return }
	p.publicKey = pub
	p.publicKeyBytes = append([]byte(nil), der...)
	// Enrollment is intentionally one-shot. Remove the token from the running
	// process after successful provisioning; operators should rotate it on restart.
	p.enrollToken = ""
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]string{"fingerprint": publisherPublicKeyFingerprint(der)})
}

func (p *publisherControl) handleIdentity(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	p.mu.Lock()
	der := append([]byte(nil), p.publicKeyBytes...)
	p.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"device_id":  p.deviceID,
		"provisioned": len(der) != 0,
		"fingerprint": publisherPublicKeyFingerprint(der),
	})
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
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
	if response.StatusCode != http.StatusNoContent && response.StatusCode != http.StatusOK && response.StatusCode != http.StatusNotFound {
		http.Error(w, "gateway rejected revoke", http.StatusBadGateway)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (p *publisherControl) serve(addr string) error {
	if !isLoopbackListenAddress(addr) {
		return fmt.Errorf("publisher control listener must be loopback, got %q", addr)
	}
	mux := http.NewServeMux()
	mux.HandleFunc(publisherControlPath, p.handleSession)
	mux.HandleFunc(publisherControlPath+"/", p.handleRevoke)
	mux.HandleFunc(publisherEnrollPath, p.handleEnroll)
	mux.HandleFunc(publisherIdentityPath, p.handleIdentity)
	server := &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 3 * time.Second, IdleTimeout: 10 * time.Second}
	return server.ListenAndServe()
}

func isLoopbackListenAddress(addr string) bool {
	host, _, err := net.SplitHostPort(strings.TrimSpace(addr))
	if err != nil { return false }
	if host == "localhost" { return true }
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func publisherPublicKeyFingerprint(raw []byte) string {
	h := sha256.Sum256(raw)
	return hex.EncodeToString(h[:])
}

func constantTime(a, b string) bool {
	if len(a) != len(b) { return false }
	var x byte
	for i := range a { x |= a[i] ^ b[i] }
	return x == 0
}
