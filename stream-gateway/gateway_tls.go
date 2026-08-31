package main

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"os"
)

// LoadGatewayServerTLSConfig constructs the production gateway TLS policy.
// A server certificate, private key, and client CA are all mandatory. The
// client-auth policy is deliberately RequireAndVerifyClientCert so a
// presented certificate cannot become an identity without chain validation.
func LoadGatewayServerTLSConfig(certFile, keyFile, clientCAFile string) (*tls.Config, error) {
	if certFile == "" || keyFile == "" || clientCAFile == "" {
		return nil, errors.New("TLS certificate, key, and client CA are required")
	}
	cert, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return nil, fmt.Errorf("load gateway certificate/key: %w", err)
	}
	caPEM, err := os.ReadFile(clientCAFile)
	if err != nil {
		return nil, fmt.Errorf("read gateway client CA: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caPEM) {
		return nil, errors.New("gateway client CA contains no valid certificates")
	}
	cfg, err := NewMutualTLSConfig(cert, pool)
	if err != nil {
		return nil, err
	}
	cfg.MinVersion = tls.VersionTLS13
	cfg.ClientAuth = tls.RequireAndVerifyClientCert
	cfg.ClientCAs = pool
	return cfg, nil
}
