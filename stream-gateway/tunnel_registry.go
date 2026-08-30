package main

import (
	"crypto/tls"
	"errors"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
)

type DeviceTunnel struct { DeviceID string; BaseURL string; client *http.Client }
type TunnelRegistry struct { mu sync.RWMutex; devices map[string]*DeviceTunnel; proxyBase string }

func NewTunnelRegistry() *TunnelRegistry {
	base := strings.TrimRight(os.Getenv("TUNNEL_PROXY_BASE"), "/")
	if base == "" { base = "http://127.0.0.1:8785/device" }
	return &TunnelRegistry{devices: make(map[string]*DeviceTunnel), proxyBase: base}
}
func (r *TunnelRegistry) Register(deviceID, baseURL string, transport http.RoundTripper) error {
	deviceID = strings.TrimSpace(deviceID); u, err := url.Parse(baseURL)
	if deviceID == "" || err != nil || u.Host == "" || u.Scheme != "http" { return errors.New("invalid device tunnel") }
	if transport == nil { transport = http.DefaultTransport }
	r.mu.Lock(); defer r.mu.Unlock(); r.devices[deviceID] = &DeviceTunnel{DeviceID:deviceID, BaseURL:strings.TrimRight(baseURL,"/"), client:&http.Client{Transport:transport,CheckRedirect:func(*http.Request,[]*http.Request)error{return http.ErrUseLastResponse}}}; return nil
}
func (r *TunnelRegistry) Get(deviceID string) (*DeviceTunnel,bool) {
	deviceID = normalizeDeviceID(deviceID); if deviceID == "" || strings.Contains(deviceID,"/") || strings.Contains(deviceID,"..") { return nil,false }
	r.mu.RLock(); t,ok:=r.devices[deviceID]; base:=r.proxyBase; r.mu.RUnlock()
	if ok { return t,true }
	u,err:=url.Parse(base+"/"+url.PathEscape(deviceID)); if err!=nil || u.Host=="" || u.Scheme!="http" { return nil,false }
	t=&DeviceTunnel{DeviceID:deviceID,BaseURL:strings.TrimRight(u.String(),"/"),client:&http.Client{Transport:http.DefaultTransport,CheckRedirect:func(*http.Request,[]*http.Request)error{return http.ErrUseLastResponse}}}
	return t,true
}
func (r *TunnelRegistry) Count() int { r.mu.RLock(); n:=len(r.devices); r.mu.RUnlock(); return n }

func NewTunnelTransport(cert tls.Certificate, roots *tls.Config, gatewayURL string) (http.RoundTripper,error) {
	u,err:=url.Parse(gatewayURL); if err!=nil || u.Scheme!="https" || u.Host=="" { return nil,errors.New("invalid tunnel gateway") }
	cfg:=roots.Clone(); cfg.Certificates=[]tls.Certificate{cert}; cfg.MinVersion=tls.VersionTLS13
	return &http.Transport{TLSClientConfig:cfg,Proxy:nil,ForceAttemptHTTP2:true},nil
}
