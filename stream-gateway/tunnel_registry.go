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

// RegisterFromProxy wires a logical device identity to the local HTTP proxy
// exposed by device-tunnel/gateway. The proxy itself owns the mTLS device
// connection; stream-gateway must never terminate or impersonate that tunnel.
func (r *TunnelRegistry) RegisterFromProxy(deviceID, proxyBaseURL string) error {
	deviceID = normalizeDeviceID(deviceID)
	if deviceID == "" || strings.Contains(deviceID, "/") || strings.Contains(deviceID, "..") {
		return errors.New("invalid device tunnel")
	}
	base, err := validateTunnelBaseURL(proxyBaseURL)
	if err != nil {
		return err
	}
	transport := &deviceProxyTransport{base: http.DefaultTransport, deviceID: deviceID}
	return r.Register(deviceID, base.String(), transport)
}

// deviceProxyTransport maps the gateway's fixed local proxy origin to
// /device/<deviceID>. The device ID is never accepted from the request path;
// it is captured when the tunnel is registered from the authenticated session.
type deviceProxyTransport struct {
	base     http.RoundTripper
	deviceID string
}

func (t *deviceProxyTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if req == nil || req.URL == nil {
		return nil, errors.New("invalid tunnel request")
	}
	if req.URL.IsAbs() == false || req.URL.Host == "" {
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

	// Production wiring is intentionally lazy: the stream gateway knows the
	// authenticated device identity only when a session is created. The local
	// tunnel broker is then selected by configuration, while the broker remains
	// responsible for proving that the device's mTLS tunnel is actually online.
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
