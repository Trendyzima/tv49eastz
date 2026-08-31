package main

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
)

// DeviceIdentityFromTLS derives device identity exclusively from the verified
// TLS peer certificate. HTTP headers are intentionally absent from this API.
func DeviceIdentityFromTLS(state *tls.ConnectionState, registry *DeviceRegistry) (DeviceRecord, error) {
	if state == nil || !state.HandshakeComplete || len(state.PeerCertificates) == 0 || len(state.VerifiedChains) == 0 {
		return DeviceRecord{}, errors.New("missing verified device certificate")
	}
	if registry == nil {
		return DeviceRecord{}, errors.New("device registry unavailable")
	}
	cert := state.PeerCertificates[0]
	if cert == nil {
		return DeviceRecord{}, errors.New("missing device certificate")
	}
	d, ok := registry.Verify(cert)
	if !ok {
		return DeviceRecord{}, errors.New("device certificate not authorized")
	}
	return d, nil
}

// NewMutualTLSConfig creates the server-side policy for device connections.
// RequireAndVerifyClientCert is mandatory: a client certificate is never
// treated as an identity merely because it was presented.
func NewMutualTLSConfig(serverCert tls.Certificate, clientCAs *x509.CertPool) (*tls.Config, error) {
	if len(serverCert.Certificate) == 0 {
		return nil, errors.New("missing server certificate")
	}
	if clientCAs == nil {
		return nil, errors.New("missing client CA pool")
	}
	return &tls.Config{MinVersion: tls.VersionTLS13, Certificates: []tls.Certificate{serverCert}, ClientAuth: tls.RequireAndVerifyClientCert, ClientCAs: clientCAs, InsecureSkipVerify: false}, nil
}
