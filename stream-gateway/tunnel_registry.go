package main

import (
	"crypto/tls"
	"errors"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"sync"
)

type DeviceTunnel struct {
	DeviceID string
	BaseURL string
	client *http.Client
}

type TunnelRegistry struct { mu sync.RWMutex; devices map[string]*DeviceTunnel }
func NewTunnelRegistry() *TunnelRegistry { return &TunnelRegistry{devices: make(map[string]*DeviceTunnel)} }
func (r *TunnelRegistry) Register(deviceID, baseURL string, transport http.RoundTripper) error {
	deviceID = strings.TrimSpace(deviceID); u, err := url.Parse(baseURL)
	if deviceID == "" || err != nil || u.Host == "" || u.Scheme != "http" { return errors.New("invalid device tunnel") }
	if transport == nil { transport = http.DefaultTransport }
	r.mu.Lock(); defer r.mu.Unlock(); r.devices[deviceID] = &DeviceTunnel{DeviceID:deviceID, BaseURL:strings.TrimRight(baseURL,"/"), client:&http.Client{Transport:transport,CheckRedirect:func(*http.Request,[]*http.Request)error{return http.ErrUseLastResponse}}}; return nil
}
func (r *TunnelRegistry) Get(deviceID string) (*DeviceTunnel,bool) { r.mu.RLock(); t,ok:=r.devices[deviceID]; r.mu.RUnlock(); return t,ok }
func (r *TunnelRegistry) Count() int { r.mu.RLock(); n:=len(r.devices); r.mu.RUnlock(); return n }

// NewTunnelTransport builds a transport for a dedicated device-side mTLS
// tunnel endpoint. The gateway never accepts a client-supplied upstream URL.
func NewTunnelTransport(cert tls.Certificate, roots *tls.Config, gatewayURL string) (http.RoundTripper,error) {
	u,err:=url.Parse(gatewayURL); if err!=nil || u.Scheme!="https" || u.Host=="" { return nil,errors.New("invalid tunnel gateway") }
	cfg:=roots.Clone(); cfg.Certificates=[]tls.Certificate{cert}; cfg.MinVersion=tls.VersionTLS13
	tr:=&http.Transport{TLSClientConfig:cfg,Proxy:nil,ForceAttemptHTTP2:true}
	return tr,nil
}

var _ = httputil.NewSingleHostReverseProxy
