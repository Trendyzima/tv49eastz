package main

import (
	"crypto/x509"
	"encoding/base64"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func testCertificateFixture(raw string) *x509.Certificate {
	now := time.Now().UTC()
	return &x509.Certificate{
		Raw:       []byte(raw),
		NotBefore: now.Add(-time.Minute),
		NotAfter:  now.Add(time.Hour),
	}
}

func TestSessionIsBoundAndExpires(t *testing.T) {
	p := Principal{UserID: "u1", DeviceID: "d1", Fingerprint: "fp1"}
	s, err := newSession(p, "ch1", "stream1", time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if s.UserID != "u1" || s.DeviceID != "d1" || s.Fingerprint != "fp1" || s.ChannelID != "ch1" || s.StreamID != "stream1" {
		t.Fatal("session is not fully bound")
	}
	if !sessionValid(s, s.IssuedAt.Add(time.Second)) {
		t.Fatal("fresh session should be valid")
	}
	if sessionValid(s, s.Expires) {
		t.Fatal("expired session must be rejected")
	}
}

func TestAuthenticateRejectsMissingCredential(t *testing.T) {
	r := httptest.NewRequest("GET", "/", nil)
	if _, err := authenticate(r, "secret"); err == nil {
		t.Fatal("missing credential accepted")
	}
}

func TestAuthenticateRejectsWrongCredential(t *testing.T) {
	r := httptest.NewRequest("GET", "/", nil)
	r.Header.Set("Authorization", "Bearer wrong")
	if _, err := authenticate(r, "secret"); err == nil {
		t.Fatal("wrong credential accepted")
	}
}

func TestAuthenticateRequiresVerifiedCertificateAndUsesCertificateIdentity(t *testing.T) {
	cert := testCertificate(t)
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

	r := httptest.NewRequest("GET", "/v1/session", nil)
	r.Header.Set("Authorization", "Bearer secret")
	r.TLS = verifiedTLSState(cert)

	p, err := authenticateWithRegistry(r, "secret", registry)
	if err != nil {
		t.Fatalf("certificate-bound authentication failed: %v", err)
	}
	if p.DeviceID != "device-a" || p.UserID != "principal-a" || p.Fingerprint != certificateFingerprint(cert) {
		t.Fatalf("identity was not derived from certificate-bound registry record: %#v", p)
	}
}

func TestAuthenticateRejectsHeaderIdentityMismatch(t *testing.T) {
	cert := testCertificate(t)
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

	r := httptest.NewRequest("GET", "/v1/session", nil)
	r.Header.Set("Authorization", "Bearer secret")
	r.Header.Set("X-Device-ID", "device-b")
	r.TLS = verifiedTLSState(cert)

	if _, err := authenticateWithRegistry(r, "secret", registry); err == nil {
		t.Fatal("certificate A with forged device-B header was accepted")
	}
}

func TestAuthenticateRejectsHeaderOnlyIdentity(t *testing.T) {
	registry := NewDeviceRegistry()
	r := httptest.NewRequest("GET", "/v1/session", nil)
	r.Header.Set("Authorization", "Bearer secret")
	r.Header.Set("X-Device-ID", "device-a")

	if _, err := authenticateWithRegistry(r, "secret", registry); err == nil {
		t.Fatal("header-only device identity was accepted")
	}
}

func TestAuthenticateRejectsRevokedCertificate(t *testing.T) {
	cert := testCertificate(t)
	registry := NewDeviceRegistry()
	if err := registry.Register(DeviceRecord{
		DeviceID:    "device-a",
		PrincipalID: "principal-a",
		Fingerprint: certificateFingerprint(cert),
		Channels:    map[string]bool{"camera": true},
		Enabled:     false,
		Revoked:     true,
	}); err != nil {
		t.Fatal(err)
	}

	r := httptest.NewRequest("GET", "/v1/session", nil)
	r.Header.Set("Authorization", "Bearer secret")
	r.TLS = verifiedTLSState(cert)
	if _, err := authenticateWithRegistry(r, "secret", registry); err == nil {
		t.Fatal("revoked certificate was accepted")
	}
}

func TestCapabilityTamperingRejected(t *testing.T) {
	g := &Gateway{cfg: Config{CapabilityKey: "test-secret"}}
	now := time.Now().UTC().Add(5 * time.Minute)
	token, err := g.signCapability("session-a", "stream-a", "/init.mp4", now)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		t.Fatal(err)
	}
	tampered := strings.Replace(string(raw), "/init.mp4", "/status", 1)
	if _, ok := g.verifyCapability(base64.RawURLEncoding.EncodeToString([]byte(tampered)), "session-a", "stream-a", now); ok {
		t.Fatal("tampered capability accepted")
	}
}

func TestExpiredCapabilityRejected(t *testing.T) {
	g := &Gateway{cfg: Config{CapabilityKey: "test-secret"}}
	expired := time.Now().UTC().Add(-time.Minute)
	token, err := g.signCapability("session-a", "stream-a", "/init.mp4", expired)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := g.verifyCapability(token, "session-a", "stream-a", expired); ok {
		t.Fatal("expired capability accepted")
	}
}

func TestRegistryCertificateBinding(t *testing.T) {
	r := NewDeviceRegistry()
	cert := testCertificateFixture("device-a-cert")
	if err := r.Register(DeviceRecord{DeviceID: "device-a", PrincipalID: "principal-a", Fingerprint: certificateFingerprint(cert), Channels: map[string]bool{"camera": true}, Enabled: true}); err != nil {
		t.Fatal(err)
	}
	if got, ok := r.Verify(cert); !ok || got.DeviceID != "device-a" {
		t.Fatal("registered certificate was not resolved")
	}
	other := testCertificateFixture("device-b-cert")
	if _, ok := r.Verify(other); ok {
		t.Fatal("unregistered certificate accepted")
	}
}

func TestDisabledDeviceRejected(t *testing.T) {
	r := NewDeviceRegistry()
	cert := testCertificateFixture("disabled-cert")
	if err := r.Register(DeviceRecord{DeviceID: "device-disabled", PrincipalID: "principal-a", Fingerprint: certificateFingerprint(cert), Channels: map[string]bool{"camera": true}, Enabled: false}); err != nil {
		t.Fatal(err)
	}
	if _, ok := r.Verify(cert); ok {
		t.Fatal("disabled device accepted")
	}
}

func TestChannelAuthorizationIsExplicit(t *testing.T) {
	d := DeviceRecord{DeviceID: "device-a", PrincipalID: "principal-a", Channels: map[string]bool{"camera": true}, Enabled: true}
	if !channelAllowed(d, "camera") {
		t.Fatal("authorized channel rejected")
	}
	if channelAllowed(d, "audio-control") {
		t.Fatal("unauthorized channel accepted")
	}
}
