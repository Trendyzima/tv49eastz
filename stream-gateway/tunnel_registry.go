package main

import (
	"crypto/tls"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"sync"
)

type DeviceTunnel struct {
	DeviceID string
	BaseURL  string
	client   *http.Client
}

type TunnelRegistry struct {
	mu      sync.RWMutex
	devices map[string]*DeviceTunnel
}

func NewTunnelRegistry() *TunnelRegistry {
	return &TunnelRegistry{devices: make(map[string]*DeviceTunnel)}
}

func validateTunnelBaseURL(raw string) (*url.URL, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Scheme != "http" || u.Host == "" {
		return nil, errors.New("invalid device tunnel")
	}
	// A tunnel endpoint is an origin, not a URL template. Reject userinfo,
	// query strings and fragments so request paths cannot be reinterpreted by
	// an intermediary or accidentally inherit attacker-controlled components.
	if u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.RawFragment != "" {
		return nil, errors.New("invalid device tunnel")
	}
	if u.Path != "" && u.Path != "/" {
		return nil, errors.New("invalid device tunnel")
	}
	u.Path = ""
	return u, nil
}

func (r *TunnelRegistry) Register(deviceID, baseURL string, transport http.RoundTripper) error {
	deviceID = normalizeDeviceID(deviceID)
	if deviceID == "" || strings.Contains(deviceID, "/") || strings.Contains(deviceID, "..") {
		return errors.New("invalid device tunnel")
	}
	u, err := validateTunnelBaseURL(baseURL)
	if err != nil {
		return err
	}
	if transport == nil {
		transport = http.DefaultTransport
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.devices[deviceID]; exists {
		return errors.New("device tunnel already registered")
	}
	r.devices[deviceID] = &DeviceTunnel{
		DeviceID: deviceID,
		BaseURL:  strings.TrimRight(u.String(), "/"),
		client: &http.Client{
			Transport: transport,
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}
	return nil
}

func (r *TunnelRegistry) Unregister(deviceID string) {
	deviceID = normalizeDeviceID(deviceID)
	r.mu.Lock()
	delete(r.devices, deviceID)
	r.mu.Unlock()
}

func (r *TunnelRegistry) Get(deviceID string) (*DeviceTunnel, bool) {
	deviceID = normalizeDeviceID(deviceID)
	if deviceID == "" || strings.Contains(deviceID, "/") || strings.Contains(deviceID, "..") {
		return nil, false
	}
	r.mu.RLock()
	t, ok := r.devices[deviceID]
	r.mu.RUnlock()
	return t, ok
}

func (r *TunnelRegistry) Count() int {
	r.mu.RLock()
	n := len(r.devices)
	r.mu.RUnlock()
	return n
}

func NewTunnelTransport(cert tls.Certificate, roots *tls.Config, gatewayURL string) (http.RoundTripper, error) {
	u, err := url.Parse(strings.TrimSpace(gatewayURL))
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return nil, errors.New("invalid tunnel gateway")
	}
	if roots == nil {
		return nil, errors.New("missing tunnel TLS configuration")
	}
	cfg := roots.Clone()
	cfg.Certificates = []tls.Certificate{cert}
	cfg.MinVersion = tls.VersionTLS13
	return &http.Transport{TLSClientConfig: cfg, Proxy: nil, ForceAttemptHTTP2: true}, nil
}
