package main

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
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
	cert, err := tls.LoadX509KeyPair(env("TUNNEL_CERT", "device.pem"), env("TUNNEL_KEY", "device-key.pem"))
	if err != nil {
		return nil, err
	}
	return &tls.Config{RootCAs: roots, Certificates: []tls.Certificate{cert}, ServerName: env("TUNNEL_SERVER_NAME", "tunnel.example"), MinVersion: tls.VersionTLS13}, nil
}

func main() {
	pool, err := strconv.Atoi(env("TUNNEL_POOL", "8"))
	if err != nil || pool < 1 || pool > 32 {
		pool = 8
	}
	deviceID := strings.TrimSpace(os.Getenv("TUNNEL_DEVICE_ID"))
	if deviceID == "" {
		panic("TUNNEL_DEVICE_ID is required")
	}
	cfg, err := tlsConfig()
	if err != nil {
		panic(err)
	}
	gateway := env("TUNNEL_GATEWAY", "gateway.example:9443")
	// server-tap's canonical listener. Override with TUNNEL_LOCAL_ADDR only
	// when the tap is deliberately deployed on a different local address.
	local := env("TUNNEL_LOCAL_ADDR", "127.0.0.1:8788")
	for i := 0; i < pool; i++ {
		go maintain(cfg, gateway, local, deviceID)
	}
	select {}
}

func maintain(cfg *tls.Config, gateway, local, deviceID string) {
	for {
		if err := connect(cfg, gateway, local, deviceID); err != nil {
			time.Sleep(2 * time.Second)
		}
	}
}

func connect(cfg *tls.Config, gateway, local, deviceID string) error {
	conn, err := tls.DialWithDialer(&net.Dialer{Timeout: 8 * time.Second, KeepAlive: 30 * time.Second}, "tcp", gateway, cfg)
	if err != nil {
		return err
	}
	defer conn.Close()
	hello := "TV49-TUNNEL/1 " + deviceID + "\n"
	if _, err = conn.Write([]byte(hello)); err != nil {
		return err
	}
	ack := make([]byte, 3)
	if _, err = io.ReadFull(conn, ack); err != nil {
		return err
	}
	if string(ack) != "OK\n" {
		return fmt.Errorf("gateway rejected tunnel")
	}
	command := make([]byte, 6)
	if _, err = io.ReadFull(conn, command); err != nil {
		return err
	}
	if string(command) != "START\n" {
		return fmt.Errorf("unexpected tunnel command")
	}
	upstream, err := net.DialTimeout("tcp", local, 5*time.Second)
	if err != nil {
		return err
	}
	defer upstream.Close()
	return proxy(conn, upstream)
}

func proxy(a, b net.Conn) error {
	done := make(chan error, 2)
	go func() { _, err := io.Copy(a, b); done <- err }()
	go func() { _, err := io.Copy(b, a); done <- err }()
	return <-done
}
