package main

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func newIdentityTestCert(t *testing.T, cn string) *x509.Certificate {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC()
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: cn},
		NotBefore:    now.Add(-time.Minute),
		NotAfter:     now.Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return cert
}

// verifiedIdentityTLSState models the state a successfully completed mTLS
// server handshake exposes to an HTTP handler. Tests must not omit
// HandshakeComplete or VerifiedChains because the production identity boundary
// intentionally rejects unverified peer certificates.
func verifiedIdentityTLSState(cert *x509.Certificate) *tls.ConnectionState {
	return &tls.ConnectionState{
		HandshakeComplete: true,
		PeerCertificates:  []*x509.Certificate{cert},
		VerifiedChains:    [][]*x509.Certificate{{cert}},
	}
}

func TestLookupByIdentityRejectsMismatchedFingerprint(t *testing.T) {
	cert := newIdentityTestCert(t, "device-a")
	other := newIdentityTestCert(t, "device-b")
	r := NewDeviceRegistry()
	if err := r.Register(DeviceRecord{DeviceID: "device-a", PrincipalID: "principal-a", Fingerprint: certificateFingerprint(cert), Channels: map[string]bool{"camera": true}, Enabled: true}); err != nil {
		t.Fatal(err)
	}
	if _, ok := r.LookupByIdentity("device-a", certificateFingerprint(other)); ok {
		t.Fatal("mismatched certificate fingerprint was authorized")
	}
	if _, ok := r.LookupByIdentity("device-a", certificateFingerprint(cert)); !ok {
		t.Fatal("matching certificate fingerprint was rejected")
	}
}

func TestAuthenticateTLSBindsPrincipalToCertificate(t *testing.T) {
	cert := newIdentityTestCert(t, "device-a")
	r := NewDeviceRegistry()
	fp := certificateFingerprint(cert)
	if err := r.Register(DeviceRecord{DeviceID: "device-a", PrincipalID: "principal-a", Fingerprint: fp, Channels: map[string]bool{"camera": true}, Enabled: true}); err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodGet, "/v1/session", nil)
	req.Header.Set("Authorization", "Bearer secret")
	req.Header.Set("X-Device-ID", "device-a")
	p, err := AuthenticateTLS(req, verifiedIdentityTLSState(cert), "secret", r)
	if err != nil {
		t.Fatal(err)
	}
	if p.DeviceID != "device-a" || p.UserID != "principal-a" || p.Fingerprint != fp {
		t.Fatalf("unexpected principal: %+v", p)
	}
}

func TestAuthenticateTLSRejectsUnverifiedTLSState(t *testing.T) {
	cert := newIdentityTestCert(t, "device-a")
	r := NewDeviceRegistry()
	fp := certificateFingerprint(cert)
	if err := r.Register(DeviceRecord{DeviceID: "device-a", PrincipalID: "principal-a", Fingerprint: fp, Channels: map[string]bool{"camera": true}, Enabled: true}); err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodGet, "/v1/session", nil)
	req.Header.Set("Authorization", "Bearer secret")
	unverified := &tls.ConnectionState{PeerCertificates: []*x509.Certificate{cert}}
	if _, err := AuthenticateTLS(req, unverified, "secret", r); err == nil {
		t.Fatal("unverified TLS state was accepted")
	}
}

func TestAuthenticateTLSRejectsHeaderIdentityMismatch(t *testing.T) {
	cert := newIdentityTestCert(t, "device-a")
	r := NewDeviceRegistry()
	if err := r.Register(DeviceRecord{DeviceID: "device-a", PrincipalID: "principal-a", Fingerprint: certificateFingerprint(cert), Channels: map[string]bool{"camera": true}, Enabled: true}); err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodGet, "/v1/session", nil)
	req.Header.Set("Authorization", "Bearer secret")
	req.Header.Set("X-Device-ID", "device-b")
	if _, err := AuthenticateTLS(req, verifiedIdentityTLSState(cert), "secret", r); err == nil {
		t.Fatal("mismatched device header was accepted")
	}
}

func TestRemoteLookupByIdentityRequiresAndMatchesFingerprint(t *testing.T) {
	cert := newIdentityTestCert(t, "device-a")
	fp := certificateFingerprint(cert)
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.URL.Path != "/registry/authorize" || req.URL.Query().Get("device_id") != "device-a" {
			http.NotFound(w, req)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"device_id": "device-a", "principal_id": "principal-a", "fingerprint": fp, "channels": map[string]bool{"camera": true}, "enabled": true, "revoked": false})
	}))
	defer ts.Close()
	r := NewDeviceRegistry()
	r.remoteURL = ts.URL
	r.client = ts.Client()
	if _, ok := r.LookupByIdentity("device-a", fp); !ok {
		t.Fatal("remote matching identity rejected")
	}
	if _, ok := r.LookupByIdentity("device-a", strings.Repeat("0", len(fp))); ok {
		t.Fatal("remote mismatched identity accepted")
	}
}

func TestRemoteLookupByIdentityRejectsMissingFingerprint(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"device_id": "device-a", "principal_id": "principal-a", "channels": map[string]bool{"camera": true}, "enabled": true, "revoked": false})
	}))
	defer ts.Close()
	r := NewDeviceRegistry()
	r.remoteURL = ts.URL
	r.client = ts.Client()
	if _, ok := r.LookupByIdentity("device-a", strings.Repeat("a", 64)); ok {
		t.Fatal("remote response without fingerprint was accepted")
	}
}
