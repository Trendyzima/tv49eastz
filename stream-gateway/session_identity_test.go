package main

import (
	"crypto/tls"
	"crypto/x509"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAuthenticateRejectsHeaderOnlyDeviceIdentity(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/v1/session?channel_id=camera&stream_id=s1", nil)
	r.Header.Set("Authorization", "Bearer secret")
	r.Header.Set("X-Device-ID", "device-a")

	if _, err := authenticateWithRegistry(r, "secret", NewDeviceRegistry()); err == nil {
		t.Fatal("header-only device identity was accepted without TLS")
	}
}

func TestAuthenticateUsesCertificateIdentityNotHeader(t *testing.T) {
	cert := newIdentityTestCert(t, "device-a")
	registry := NewDeviceRegistry()
	fp := certificateFingerprint(cert)
	if err := registry.Register(DeviceRecord{
		DeviceID:    "device-a",
		PrincipalID: "principal-a",
		Fingerprint: fp,
		Channels:    map[string]bool{"camera": true},
		Enabled:     true,
	}); err != nil {
		t.Fatal(err)
	}

	r := httptest.NewRequest(http.MethodGet, "/v1/session?channel_id=camera&stream_id=s1", nil)
	r.Header.Set("Authorization", "Bearer secret")
	r.Header.Set("X-Device-ID", "device-a")
	r.TLS = &tls.ConnectionState{PeerCertificates: []*x509.Certificate{cert}}

	p, err := authenticateWithRegistry(r, "secret", registry)
	if err != nil {
		t.Fatal(err)
	}
	if p.DeviceID != "device-a" || p.UserID != "principal-a" || p.Fingerprint != fp {
		t.Fatalf("principal was not certificate-bound: %+v", p)
	}
}

func TestAuthenticateRejectsForgedDeviceHeader(t *testing.T) {
	cert := newIdentityTestCert(t, "device-a")
	registry := NewDeviceRegistry()
	if err := registry.Register(DeviceRecord{
		DeviceID:    "device-a",
		PrincipalID: "principal-a",
		Fingerprint: certificateFingerprint(cert),
		Channels:    map[string]bool{"camera": true},
		Enabled:     true,
	}); err != nil {
		t.Fatal(err)
	}

	r := httptest.NewRequest(http.MethodGet, "/v1/session?channel_id=camera&stream_id=s1", nil)
	r.Header.Set("Authorization", "Bearer secret")
	r.Header.Set("X-Device-ID", "device-b")
	r.TLS = &tls.ConnectionState{PeerCertificates: []*x509.Certificate{cert}}

	if _, err := authenticateWithRegistry(r, "secret", registry); err == nil {
		t.Fatal("forged device header was accepted")
	}
}
