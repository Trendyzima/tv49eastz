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
	if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return nil, errors.New("invalid device tunnel")
	}
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

// RegisterFromProxy wires a logical device identity to the authenticated
// HTTP proxy exposed by the Cloudflare relay. The relay owns the WebSocket
// device connection; stream-gateway only forwards media requests through it.
func (r *TunnelRegistry) RegisterFromProxy(deviceID, proxyBaseURL string) error {
	deviceID = normalizeDeviceID(deviceID)
	if deviceID == "" || strings.Contains(deviceID, "/") || strings.Contains(deviceID, "..") {
		return errors.New("invalid device tunnel")
	}
	base, err := validateTunnelBaseURL(proxyBaseURL)
	if err != nil {
		return err
	}
	transport := &deviceProxyTransport{
		base:     http.DefaultTransport,
		deviceID: deviceID,
		auth:     strings.TrimSpace(os.Getenv("TUNNEL_PROXY_AUTH")),
	}
	return r.Register(deviceID, base.String(), transport)
}

// deviceProxyTransport maps the gateway's fixed proxy origin to
// /device/<deviceID>. Authentication is injected by the gateway process so
// the public Worker endpoint cannot be used as an unauthenticated device proxy.
type deviceProxyTransport struct {
	base     http.RoundTripper
	deviceID string
	auth     string
}

func (t *deviceProxyTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if req == nil || req.URL == nil {
		return nil, errors.New("invalid tunnel request")
	}
	if !req.URL.IsAbs() || req.URL.Host == "" {
		return nil, errors.New("invalid tunnel request URL")
	}
	clone := req.Clone(req.Context())
	u := *req.URL
	prefix := "/device/" + url.PathEscape(t.deviceID)
	if req.URL.Path == "" {
		u.Path = prefix
	} else {
		u.Path = prefix + req.URL.Path
	}
	u.RawPath = ""
	clone.URL = &u
	if t.auth != "" {
		clone.Header.Set("Authorization", "Bearer "+t.auth)
	}
	return t.base.RoundTrip(clone)
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
	if ok {
		return t, true
	}

	proxyBase := strings.TrimSpace(env("TUNNEL_PROXY_BASE_URL", ""))
	if proxyBase == "" {
		return nil, false
	}
	if err := r.RegisterFromProxy(deviceID, proxyBase); err != nil {
		return nil, false
	}
	r.mu.RLock()
	t, ok = r.devices[deviceID]
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
