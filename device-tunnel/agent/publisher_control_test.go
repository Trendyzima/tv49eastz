package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"fmt"
	"testing"
	"time"
)

func TestPublisherRequestSignatureAndReplay(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil { t.Fatal(err) }
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil { t.Fatal(err) }
	p := &publisherControl{deviceID: "device-1", publicKey: &key.PublicKey, publicKeyBytes: der, nonces: make(map[string]time.Time)}
	issued := time.Now().Unix()
	nonce := "nonce-1"
	canonical := fmt.Sprintf("1|%s|%d|device-1|fadcam-local|camera", nonce, issued)
	digest := sha256.Sum256([]byte(canonical))
	sig, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil { t.Fatal(err) }
	req := publisherRequest{Version: 1, Nonce: nonce, IssuedAt: issued, DeviceID: "device-1", ChannelID: "fadcam-local", StreamID: "camera", PublicKey: base64.RawURLEncoding.EncodeToString(der), Signature: base64.RawURLEncoding.EncodeToString(sig)}
	if err := p.verifyRequest(req); err != nil { t.Fatalf("valid request rejected: %v", err) }
	if err := p.verifyRequest(req); err == nil { t.Fatal("replayed request accepted") }
}

func TestPublisherRequestRejectsWrongDevice(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil { t.Fatal(err) }
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil { t.Fatal(err) }
	p := &publisherControl{deviceID: "device-1", publicKey: &key.PublicKey, publicKeyBytes: der, nonces: make(map[string]time.Time)}
	req := publisherRequest{Version: 1, Nonce: "nonce-2", IssuedAt: time.Now().Unix(), DeviceID: "device-2", ChannelID: "fadcam-local", StreamID: "camera", PublicKey: base64.RawURLEncoding.EncodeToString(der), Signature: "bad"}
	if err := p.verifyRequest(req); err == nil { t.Fatal("wrong device accepted") }
}

func TestPublisherControlLoopbackAddress(t *testing.T) {
	valid := []string{"127.0.0.1:8789", "127.0.0.2:1", "[::1]:8789", "localhost:8789"}
	for _, addr := range valid {
		if !isLoopbackListenAddress(addr) { t.Errorf("expected loopback address accepted: %s", addr) }
	}
	invalid := []string{"0.0.0.0:8789", ":8789", "192.168.1.10:8789", "10.0.0.2:8789", "[::]:8789", "bad"}
	for _, addr := range invalid {
		if isLoopbackListenAddress(addr) { t.Errorf("expected non-loopback address rejected: %s", addr) }
	}
}
