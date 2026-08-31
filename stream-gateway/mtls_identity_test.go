package main

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"testing"
	"time"
)

func testCertificate(t *testing.T) *x509.Certificate {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	tpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "device-a"}, NotBefore: now.Add(-time.Minute), NotAfter: now.Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth}}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return cert
}

func verifiedTLSState(cert *x509.Certificate) *tls.ConnectionState {
	return &tls.ConnectionState{
		HandshakeComplete: true,
		PeerCertificates:  []*x509.Certificate{cert},
		VerifiedChains:    [][]*x509.Certificate{{cert}},
	}
}

func TestDeviceIdentityFromTLSUsesCertificateFingerprint(t *testing.T) {
	cert := testCertificate(t)
	r := NewDeviceRegistry()
	if err := r.Register(DeviceRecord{DeviceID: "device-a", PrincipalID: "p", Fingerprint: certificateFingerprint(cert), Channels: map[string]bool{"camera": true}, Enabled: true}); err != nil {
		t.Fatal(err)
	}
	d, err := DeviceIdentityFromTLS(verifiedTLSState(cert), r)
	if err != nil || d.DeviceID != "device-a" {
		t.Fatalf("identity binding failed: %#v %v", d, err)
	}
}

func TestDeviceIdentityFromTLSRejectsUnregisteredCertificate(t *testing.T) {
	cert := testCertificate(t)
	r := NewDeviceRegistry()
	if _, err := DeviceIdentityFromTLS(verifiedTLSState(cert), r); err == nil {
		t.Fatal("accepted unregistered certificate")
	}
}

func TestDeviceIdentityFromTLSRejectsUnverifiedState(t *testing.T) {
	cert := testCertificate(t)
	r := NewDeviceRegistry()
	if err := r.Register(DeviceRecord{DeviceID: "device-a", PrincipalID: "p", Fingerprint: certificateFingerprint(cert), Channels: map[string]bool{"camera": true}, Enabled: true}); err != nil {
		t.Fatal(err)
	}
	if _, err := DeviceIdentityFromTLS(&tls.ConnectionState{PeerCertificates: []*x509.Certificate{cert}}, r); err == nil {
		t.Fatal("accepted certificate without completed verified TLS state")
	}
}

func TestNewMutualTLSConfigRequiresClientCA(t *testing.T) {
	if _, err := NewMutualTLSConfig(tls.Certificate{Certificate: [][]byte{{1}}}, nil); err == nil {
		t.Fatal("accepted missing client CA")
	}
}
