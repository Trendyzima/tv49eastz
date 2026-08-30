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

func TestBrokerBindsMultipleDevicesToDistinctPools(t *testing.T) {
	reg := testDeviceRegistry("device-a", "device-b")
	b := newBroker(2, reg)
	a := &x509.Certificate{Raw: []byte("cert-device-a"), Subject: pkix.Name{CommonName: "device-a"}}
	bb := &x509.Certificate{Raw: []byte("cert-device-b"), Subject: pkix.Name{CommonName: "device-b"}}

	idA, err := b.registry.bind(a)
	if err != nil || idA != "device-a" {
		t.Fatalf("bind A: %v %q", err, idA)
	}
	idB, err := b.registry.bind(bb)
	if err != nil || idB != "device-b" {
		t.Fatalf("bind B: %v %q", err, idB)
	}

	b.mu.Lock()
	b.devices[idA] = newDevicePool(2)
	b.devices[idB] = newDevicePool(2)
	b.mu.Unlock()

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
