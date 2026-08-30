package main

import (
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"testing"
)

func testDeviceRegistry(ids ...string) *DeviceRegistry {
	devices := make(map[string]DeviceRecord, len(ids))
	for _, id := range ids {
		raw := []byte("cert-" + id)
		sum := sha256.Sum256(raw)
		devices[id] = DeviceRecord{
			DeviceID:    id,
			PrincipalID: "principal-" + id,
			Fingerprint: hex.EncodeToString(sum[:]),
			Enabled:     true,
		}
	}
	return &DeviceRegistry{devices: devices, subscribers: make(map[chan DeviceEvent]struct{})}
}

func attachTestDevice(t *testing.T, b *broker, cert *x509.Certificate) string {
	t.Helper()
	id, err := b.registry.bind(cert)
	if err != nil {
		t.Fatal(err)
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if _, exists := b.devices[id]; exists {
		t.Fatalf("device %q already attached", id)
	}
	b.devices[id] = newDevicePool(b.poolSize)
	return id
}

func TestBrokerBindsMultipleDevicesToDistinctPools(t *testing.T) {
	reg := testDeviceRegistry("device-a", "device-b")
	b := newBroker(2, reg)
	a := &x509.Certificate{Raw: []byte("cert-device-a"), Subject: pkix.Name{CommonName: "device-a"}}
	bb := &x509.Certificate{Raw: []byte("cert-device-b"), Subject: pkix.Name{CommonName: "device-b"}}

	idA := attachTestDevice(t, b, a)
	idB := attachTestDevice(t, b, bb)

	if idA != "device-a" || idB != "device-b" {
		t.Fatalf("unexpected device IDs: %q %q", idA, idB)
	}
	if b.count() != 2 {
		t.Fatalf("device count=%d", b.count())
	}
	pa, _ := b.pool("device-a")
	pb, _ := b.pool("device-b")
	if pa == pb {
		t.Fatal("devices share the same pool")
	}
}

func TestBrokerRejectsCertificateSwapForExistingDevice(t *testing.T) {
	reg := testDeviceRegistry("device-a")
	b := newBroker(1, reg)
	first := &x509.Certificate{Raw: []byte("cert-device-a"), Subject: pkix.Name{CommonName: "device-a"}}
	second := &x509.Certificate{Raw: []byte("different-cert"), Subject: pkix.Name{CommonName: "device-a"}}
	if _, err := b.registry.bind(first); err != nil {
		t.Fatal(err)
	}
	if _, err := b.registry.bind(second); err == nil {
		t.Fatal("certificate swap accepted")
	}
}
