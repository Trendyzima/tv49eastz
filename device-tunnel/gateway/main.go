package main

import (
	"bufio"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

type DeviceRecord struct {
	DeviceID    string          `json:"device_id"`
	PrincipalID string          `json:"principal_id"`
	Fingerprint string          `json:"fingerprint"`
	Channels    map[string]bool `json:"channels,omitempty"`
	Enabled     bool            `json:"enabled"`
	RevokedAt   *time.Time      `json:"revoked_at,omitempty"`
}
type DeviceRegistry struct {
	mu          sync.RWMutex
	path        string
	devices     map[string]DeviceRecord
	subscribers map[chan DeviceEvent]struct{}
}
type DeviceEvent struct {
	DeviceID string
	Revoked  bool
	Enabled  bool
}

func loadRegistry(path string) (*DeviceRegistry, error) {
	r := &DeviceRegistry{path: path, devices: map[string]DeviceRecord{}, subscribers: map[chan DeviceEvent]struct{}{}}
	b, e := os.ReadFile(path)
	if os.IsNotExist(e) {
		return r, nil
	}
	if e != nil {
		return nil, e
	}
	if len(b) > 0 {
		if e = json.Unmarshal(b, &r.devices); e != nil {
			return nil, e
		}
	}
	return r, nil
}

func (r *DeviceRegistry) persistLocked() error {
	tmp := r.path + ".tmp"
	b, e := json.MarshalIndent(r.devices, "", "  ")
	if e != nil {
		return e
	}
	if e = os.WriteFile(tmp, b, 0600); e != nil {
		return e
	}
	return os.Rename(tmp, r.path)
}

func (r *DeviceRegistry) bind(cert *x509.Certificate) (string, error) {
	if cert == nil {
		return "", errors.New("missing client certificate")
	}
	id := strings.TrimSpace(cert.Subject.CommonName)
	if id == "" {
		return "", errors.New("certificate has no device identity")
	}
	fp := fingerprint(cert)
	r.mu.RLock()
	d, ok := r.devices[id]
	r.mu.RUnlock()
	if !ok {
		return "", errors.New("device is not enrolled")
	}
	if d.RevokedAt != nil || !d.Enabled {
		return "", errors.New("device is revoked or disabled")
	}
	if !strings.EqualFold(d.Fingerprint, fp) {
		return "", errors.New("certificate does not match enrolled device")
	}
	return id, nil
}

func (r *DeviceRegistry) authorize(id string) (DeviceRecord, bool) {
	r.mu.RLock()
	d, ok := r.devices[id]
	r.mu.RUnlock()
	return d, ok && d.Enabled && d.RevokedAt == nil
}

func fingerprint(cert *x509.Certificate) string {
	sum := sha256.Sum256(cert.Raw)
	return hex.EncodeToString(sum[:])
}

func (r *DeviceRegistry) revoke(id string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	d, ok := r.devices[id]
	if !ok {
		return errors.New("device not enrolled")
	}
	now := time.Now().UTC()
	d.RevokedAt = &now
	d.Enabled = false
	r.devices[id] = d
	if e := r.persistLocked(); e != nil {
		return e
	}
	for ch := range r.subscribers {
		select {
		case ch <- DeviceEvent{DeviceID: id, Revoked: true, Enabled: false}:
		default:
		}
	}
	return nil
}

func (r *DeviceRegistry) subscribe(buffer int) (<-chan DeviceEvent, func()) {
	if buffer < 1 {
		buffer = 1
	}
	ch := make(chan DeviceEvent, buffer)
	r.mu.Lock()
	r.subscribers[ch] = struct{}{}
	r.mu.Unlock()
	return ch, func() {
		r.mu.Lock()
	if _, ok := r.subscribers[ch]; ok {
			delete(r.subscribers, ch)
			close(ch)
		}
		r.mu.Unlock()
	}
}

type devicePool struct {
	mu     sync.Mutex
	conns  chan net.Conn
	active map[net.Conn]struct{}
	closed bool
}

func newDevicePool(size int) *devicePool {
	return &devicePool{conns: make(chan net.Conn, size), active: make(map[net.Conn]struct{})}
}

func (p *devicePool) add(c net.Conn) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		_ = c.Close()
		return
	}
	select {
	case p.conns <- c:
	default:
		_ = c.Close()
	}
}

func (p *devicePool) take(timeout time.Duration) (net.Conn, error) {
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	for {
		p.mu.Lock()
		if p.closed {
			p.mu.Unlock()
			return nil, errors.New("device tunnel unavailable")
		}
		p.mu.Unlock()

		select {
		case c, ok := <-p.conns:
			if !ok || c == nil {
				return nil, errors.New("device tunnel unavailable")
			}
			p.mu.Lock()
			if p.closed {
				p.mu.Unlock()
				_ = c.Close()
				return nil, errors.New("device tunnel unavailable")
			}
			p.active[c] = struct{}{}
			p.mu.Unlock()
			return c, nil
		case <-deadline.C:
			return nil, errors.New("device tunnel unavailable")
		}
	}
}

func (p *devicePool) release(c net.Conn) {
	if c == nil {
		return
	}
	p.mu.Lock()
	delete(p.active, c)
	p.mu.Unlock()
}

func (p *devicePool) closeAll() {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return
	}
	p.closed = true
	queued := make([]net.Conn, 0, len(p.conns))
	for {
		select {
		case c := <-p.conns:
			if c != nil {
				queued = append(queued, c)
			}
		default:
			goto drained
		}
	}

drained:
	active := make([]net.Conn, 0, len(p.active))
	for c := range p.active {
		active = append(active, c)
	}
	p.active = make(map[net.Conn]struct{})
	p.mu.Unlock()
	for _, c := range queued {
		_ = c.Close()
	}
	for _, c := range active {
		_ = c.Close()
	}
}

type broker struct {
	mu       sync.RWMutex
	devices  map[string]*devicePool
	registry *DeviceRegistry
	poolSize int
}

func newBroker(poolSize int, r *DeviceRegistry) *broker {
	return &broker{devices: make(map[string]*devicePool), registry: r, poolSize: poolSize}
}

func (b *broker) pool(id string) (*devicePool, bool) {
	b.mu.RLock()
	p, ok := b.devices[id]
	b.mu.RUnlock()
	return p, ok
}

func (b *broker) count() int {
	b.mu.RLock()
	n := len(b.devices)
	b.mu.RUnlock()
	return n
}

func (b *broker) startRevocationWatcher() {
	events, unsubscribe := b.registry.subscribe(16)
	go func() {
		defer unsubscribe()
		for ev := range events {
			if ev.Revoked || !ev.Enabled {
				b.mu.Lock()
				p := b.devices[ev.DeviceID]
				delete(b.devices, ev.DeviceID)
				b.mu.Unlock()
				if p != nil {
					p.closeAll()
				}
			}
		}
	}()
}

func tlsConfig() (*tls.Config, error) {
	caPEM, e := os.ReadFile(env("TUNNEL_CA", "ca.pem"))
	if e != nil {
		return nil, e
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(caPEM) {
		return nil, errors.New("invalid tunnel CA")
	}
	cert, e := tls.LoadX509KeyPair(env("TUNNEL_CERT", "gateway.pem"), env("TUNNEL_KEY", "gateway-key.pem"))
	if e != nil {
		return nil, e
	}
	return &tls.Config{Certificates: []tls.Certificate{cert}, ClientCAs: roots, ClientAuth: tls.RequireAndVerifyClientCert, MinVersion: tls.VersionTLS13}, nil
}

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func main() {
	cfg, e := tlsConfig()
	if e != nil {
		log.Fatal(e)
	}
	size, _ := strconv.Atoi(env("TUNNEL_POOL", "8"))
	if size < 1 || size > 64 {
		size = 8
	}
	reg, e := loadRegistry(env("DEVICE_REGISTRY_PATH", "devices.json"))
	if e != nil {
		log.Fatal(e)
	}
	b := newBroker(size, reg)
	b.startRevocationWatcher()
	ln, e := tls.Listen("tcp", env("TUNNEL_LISTEN", ":9443"), cfg)
	if e != nil {
		log.Fatal(e)
	}
	defer ln.Close()
	go acceptDevices(ln, b)
	mux := http.NewServeMux()
	mux.HandleFunc("/health", b.health)
	mux.HandleFunc("/device/", b.proxyHTTP)
	mux.HandleFunc("/registry/authorize", b.registryAuthorize)
	srv := &http.Server{Addr: env("TUNNEL_PROXY_LISTEN", "127.0.0.1:8785"), Handler: mux, ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second}
	log.Printf("multi-device tunnel broker listening on %s; proxy=%s", ln.Addr(), srv.Addr)
	log.Fatal(srv.ListenAndServe())
}

func acceptDevices(ln net.Listener, b *broker) {
	for {
		c, e := ln.Accept()
		if e != nil {
			time.Sleep(time.Second)
			continue
		}
		go register(c, b)
	}
}

func register(c net.Conn, b *broker) {
	state, ok := c.(*tls.Conn)
	if !ok {
		_ = c.Close()
		return
	}
	_ = state.SetDeadline(time.Now().Add(10 * time.Second))
	if e := state.Handshake(); e != nil {
		_ = c.Close()
		return
	}
	cs := state.ConnectionState()
	if len(cs.PeerCertificates) == 0 {
		_ = c.Close()
		return
	}
	deviceID, e := b.registry.bind(cs.PeerCertificates[0])
	if e != nil {
		_ = c.Close()
		return
	}
	br := bufio.NewReaderSize(c, 256)
	hello, e := br.ReadString('\n')
	if e != nil {
		_ = c.Close()
		return
	}
	if strings.TrimSpace(hello) != "TV49-TUNNEL/1 "+deviceID {
		_ = c.Close()
		return
	}
	if _, e = c.Write([]byte("OK\n")); e != nil {
		_ = c.Close()
		return
	}
	_ = c.SetDeadline(time.Time{})
	p := newDevicePool(b.poolSize)
	b.mu.Lock()
	if _, exists := b.devices[deviceID]; exists {
		b.mu.Unlock()
		p.closeAll()
		_ = c.Close()
		return
	}
	b.devices[deviceID] = p
	b.mu.Unlock()
	p.add(c)
}

func (b *broker) proxyHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/device/")
	i := strings.IndexByte(path, '/')
	if i <= 0 {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	deviceID, target := path[:i], path[i:]
	if strings.Contains(deviceID, "..") || strings.Contains(target, "..") || strings.HasPrefix(target, "//") {
		http.Error(w, "bad path", http.StatusBadRequest)
		return
	}
	if _, ok := b.registry.authorize(deviceID); !ok {
		http.Error(w, "device unavailable", http.StatusServiceUnavailable)
		return
	}
	p, ok := b.pool(deviceID)
	if !ok {
		http.Error(w, "device unavailable", http.StatusServiceUnavailable)
		return
	}
	conn, e := p.take(3 * time.Second)
	if e != nil {
		http.Error(w, "device unavailable", http.StatusServiceUnavailable)
		return
	}
	defer func() {
		p.release(conn)
		_ = conn.Close()
	}()
	_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, e = conn.Write([]byte("START\n")); e != nil {
		http.Error(w, "device unavailable", http.StatusBadGateway)
		return
	}
	_ = conn.SetDeadline(time.Time{})
	u := &url.URL{Path: target}
	if r.URL.RawQuery != "" {
		u.RawQuery = r.URL.RawQuery
	}
	req := &http.Request{Method: http.MethodGet, URL: u, Proto: "HTTP/1.1", ProtoMajor: 1, ProtoMinor: 1, Header: make(http.Header), Host: "fadcam.local"}
	if e = req.Write(conn); e != nil {
		http.Error(w, "device request failed", http.StatusBadGateway)
		return
	}
	resp, e := http.ReadResponse(bufio.NewReader(conn), req)
	if e != nil {
		http.Error(w, "device response failed", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	for k, vv := range resp.Header {
		for _, v := range vv {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}

func (b *broker) registryAuthorize(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	id := r.URL.Query().Get("device_id")
	d, ok := b.registry.authorize(id)
	if !ok {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"device_id": d.DeviceID, "principal_id": d.PrincipalID, "fingerprint": d.Fingerprint, "enabled": d.Enabled, "revoked": d.RevokedAt != nil, "channels": d.Channels})
}

func (b *broker) health(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_, _ = io.WriteString(w, `{"ok":true,"devices":`+strconv.Itoa(b.count())+`}`)
}
