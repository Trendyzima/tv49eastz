package main

import (
	"bufio"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
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

func env(key, fallback string) string { if v := os.Getenv(key); v != "" { return v }; return fallback }

type devicePool struct { mu sync.Mutex; conns chan net.Conn }
func newDevicePool(size int) *devicePool { return &devicePool{conns: make(chan net.Conn, size)} }
func (p *devicePool) add(c net.Conn) { select { case p.conns <- c: default: _ = c.Close() } }
func (p *devicePool) take(timeout time.Duration) (net.Conn, error) { select { case c := <-p.conns: return c, nil; case <-time.After(timeout): return nil, errors.New("device tunnel unavailable") } }
func (p *devicePool) size() int { return len(p.conns) }

type broker struct { mu sync.RWMutex; devices map[string]*devicePool; fingerprints map[string]string; poolSize int }
func newBroker(poolSize int) *broker { return &broker{devices: make(map[string]*devicePool), fingerprints: make(map[string]string), poolSize: poolSize} }
func (b *broker) bind(cert *x509.Certificate) (string, error) {
	if cert == nil { return "", errors.New("missing client certificate") }
	id := strings.TrimSpace(cert.Subject.CommonName); if id == "" { return "", errors.New("certificate has no device identity") }
	fp := fingerprint(cert)
	b.mu.Lock(); defer b.mu.Unlock()
	if old, ok := b.fingerprints[id]; ok && old != fp { return "", errors.New("device identity is bound to a different certificate") }
	if _, ok := b.devices[id]; !ok { b.devices[id] = newDevicePool(b.poolSize); b.fingerprints[id] = fp }
	return id, nil
}
func (b *broker) pool(id string) (*devicePool, bool) { b.mu.RLock(); p, ok := b.devices[id]; b.mu.RUnlock(); return p, ok }
func (b *broker) count() int { b.mu.RLock(); n := len(b.devices); b.mu.RUnlock(); return n }
func fingerprint(cert *x509.Certificate) string { sum := sha256.Sum256(cert.Raw); return hex.EncodeToString(sum[:]) }

func tlsConfig() (*tls.Config, error) {
	caPEM, err := os.ReadFile(env("TUNNEL_CA", "ca.pem")); if err != nil { return nil, err }
	roots := x509.NewCertPool(); if !roots.AppendCertsFromPEM(caPEM) { return nil, errors.New("invalid tunnel CA") }
	cert, err := tls.LoadX509KeyPair(env("TUNNEL_CERT", "gateway.pem"), env("TUNNEL_KEY", "gateway-key.pem")); if err != nil { return nil, err }
	return &tls.Config{Certificates: []tls.Certificate{cert}, ClientCAs: roots, ClientAuth: tls.RequireAndVerifyClientCert, MinVersion: tls.VersionTLS13}, nil
}

func main() {
	cfg, err := tlsConfig(); if err != nil { log.Fatal(err) }
	poolSize, _ := strconv.Atoi(env("TUNNEL_POOL", "8")); if poolSize < 1 || poolSize > 64 { poolSize = 8 }
	b := newBroker(poolSize)
	ln, err := tls.Listen("tcp", env("TUNNEL_LISTEN", ":9443"), cfg); if err != nil { log.Fatal(err) }; defer ln.Close()
	go acceptDevices(ln, b)
	mux := http.NewServeMux(); mux.HandleFunc("/health", b.health); mux.HandleFunc("/device/", b.proxyHTTP)
	srv := &http.Server{Addr: env("TUNNEL_PROXY_LISTEN", "127.0.0.1:8785"), Handler: mux, ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second}
	log.Printf("multi-device tunnel broker listening on %s; HTTP proxy=%s", env("TUNNEL_LISTEN", ":9443"), srv.Addr)
	log.Fatal(srv.ListenAndServe())
}
func acceptDevices(ln net.Listener, b *broker) { for { c, err := ln.Accept(); if err != nil { time.Sleep(time.Second); continue }; go register(c, b) } }
func register(c net.Conn, b *broker) {
	state, ok := c.(*tls.Conn); if !ok { _ = c.Close(); return }; _ = state.SetDeadline(time.Now().Add(10*time.Second))
	if err := state.Handshake(); err != nil { _ = c.Close(); return }; cs := state.ConnectionState(); if len(cs.PeerCertificates)==0 { _=c.Close(); return }
	deviceID, err := b.bind(cs.PeerCertificates[0]); if err != nil { _=c.Close(); return }
	br := bufio.NewReaderSize(c,256); hello, err := br.ReadString('\n'); if err != nil { _=c.Close(); return }
	if strings.TrimSpace(hello) != "TV49-TUNNEL/1 "+deviceID { _=c.Close(); return }
	if _, err = c.Write([]byte("OK\n")); err != nil { _=c.Close(); return }; _=c.SetDeadline(time.Time{})
	if p, ok := b.pool(deviceID); ok { p.add(c) } else { _=c.Close() }
}
func (b *broker) proxyHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet { http.Error(w,"method not allowed",405); return }
	path := strings.TrimPrefix(r.URL.Path,"/device/"); i:=strings.IndexByte(path,'/'); if i<=0 { http.Error(w,"not found",404); return }
	deviceID,targetPath:=path[:i],path[i:]; if strings.Contains(deviceID,"..")||strings.Contains(targetPath,"..")||strings.HasPrefix(targetPath,"//") { http.Error(w,"bad path",400); return }
	p,ok:=b.pool(deviceID); if !ok { http.Error(w,"device unavailable",503); return }; conn,err:=p.take(3*time.Second); if err!=nil { http.Error(w,"device unavailable",503); return }; defer conn.Close()
	_ = conn.SetWriteDeadline(time.Now().Add(5*time.Second)); if _,err=conn.Write([]byte("START\n")); err!=nil { http.Error(w,"device unavailable",503); return }; _=conn.SetDeadline(time.Time{})
	u:=&url.URL{Path:targetPath}; if r.URL.RawQuery!="" { u.RawQuery=r.URL.RawQuery }
	req:=&http.Request{Method:http.MethodGet,URL:u,Proto:"HTTP/1.1",ProtoMajor:1,ProtoMinor:1,Header:make(http.Header),Host:"fadcam.local"}
	if err=req.Write(conn); err!=nil { http.Error(w,"device request failed",502); return }
	resp,err:=http.ReadResponse(bufio.NewReader(conn),req); if err!=nil { http.Error(w,"device response failed",502); return }; defer resp.Body.Close()
	for k,vv:=range resp.Header { for _,v:=range vv { w.Header().Add(k,v) } }; w.WriteHeader(resp.StatusCode); _,_=io.Copy(w,resp.Body)
}
func (b *broker) health(w http.ResponseWriter,r *http.Request) { w.Header().Set("Content-Type","application/json"); io.WriteString(w,`{"ok":true,"devices":`+strconv.Itoa(b.count())+`}`) }
