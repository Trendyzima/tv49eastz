package main

import (
	"bufio"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

type pool struct {
	mu    sync.Mutex
	conns chan net.Conn
}

func (p *pool) put(c net.Conn) {
	select {
	case p.conns <- c:
	default:
		_ = c.Close()
	}
}
func (p *pool) get(timeout time.Duration) (net.Conn, error) {
	select {
	case c := <-p.conns:
		return c, nil
	case <-time.After(timeout):
		return nil, errors.New("no device tunnel available")
	}
}

func tlsConfig() (*tls.Config, error) {
	caPEM, err := os.ReadFile(env("TUNNEL_CA", "ca.pem"))
	if err != nil {
		return nil, err
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(caPEM) {
		return nil, errors.New("invalid tunnel CA")
	}
	cert, err := tls.LoadX509KeyPair(env("TUNNEL_CERT", "gateway.pem"), env("TUNNEL_KEY", "gateway-key.pem"))
	if err != nil {
		return nil, err
	}
	return &tls.Config{Certificates: []tls.Certificate{cert}, ClientCAs: roots, ClientAuth: tls.RequireAndVerifyClientCert, MinVersion: tls.VersionTLS13}, nil
}

func main() {
	cfg, err := tlsConfig()
	if err != nil {
		panic(err)
	}
	listen := env("TUNNEL_LISTEN", ":9443")
	local := env("TUNNEL_FORWARD_LISTEN", "127.0.0.1:8786")
	deviceID := strings.TrimSpace(os.Getenv("TUNNEL_DEVICE_ID"))
	if deviceID == "" {
		panic("TUNNEL_DEVICE_ID is required")
	}
	size, _ := strconv.Atoi(env("TUNNEL_POOL", "8"))
	if size < 1 || size > 32 {
		size = 8
	}
	ln, err := tls.Listen("tcp", listen, cfg)
	if err != nil {
		panic(err)
	}
	defer ln.Close()
	p := &pool{conns: make(chan net.Conn, size)}
	go acceptDevices(ln, p, deviceID)
	forward, err := net.Listen("tcp", local)
	if err != nil {
		panic(err)
	}
	defer forward.Close()
	for {
		client, err := forward.Accept()
		if err != nil {
			continue
		}
		go serve(client, p)
	}
}

func acceptDevices(ln net.Listener, p *pool, expected string) {
	for {
		c, err := ln.Accept()
		if err != nil {
			time.Sleep(time.Second)
			continue
		}
		go register(c, p, expected)
	}
}

func register(c net.Conn, p *pool, expected string) {
	defer func() {
		if r := recover(); r != nil {
			_ = c.Close()
		}
	}()
	_ = c.SetDeadline(time.Now().Add(10 * time.Second))
	state, ok := c.(*tls.Conn)
	if !ok {
		_ = c.Close()
		return
	}
	if err := state.Handshake(); err != nil {
		_ = c.Close()
		return
	}
	cs := state.ConnectionState()
	if len(cs.PeerCertificates) == 0 {
		_ = c.Close()
		return
	}
	cert := cs.PeerCertificates[0]
	certID := strings.TrimSpace(cert.Subject.CommonName)
	if certID == "" || certID != expected {
		_ = c.Close()
		return
	}
	br := bufio.NewReaderSize(c, 256)
	hello, err := br.ReadString('\n')
	if err != nil {
		_ = c.Close()
		return
	}
	hello = strings.TrimSpace(hello)
	want := "TV49-TUNNEL/1 " + certID
	if hello != want {
		_ = c.Close()
		return
	}
	if _, err = c.Write([]byte("OK\n")); err != nil {
		_ = c.Close()
		return
	}
	_ = c.SetDeadline(time.Time{})
	p.put(c)
}
func serve(client net.Conn, p *pool) {
	defer client.Close()
	tunnel, err := p.get(3 * time.Second)
	if err != nil {
		return
	}
	defer tunnel.Close()
	_ = tunnel.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err = tunnel.Write([]byte("START\n")); err != nil {
		return
	}
	_ = tunnel.SetDeadline(time.Time{})
	proxy(client, tunnel)
}
func proxy(a, b net.Conn) {
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(a, b); done <- struct{}{} }()
	go func() { _, _ = io.Copy(b, a); done <- struct{}{} }()
	<-done
}

var _ = fmt.Sprintf
