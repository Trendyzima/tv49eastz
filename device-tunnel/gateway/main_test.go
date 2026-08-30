package main

import (
	"crypto/x509"
	"testing"
)

func TestBrokerBindsMultipleDevicesToDistinctPools(t *testing.T) {
	b := newBroker(2)
	a := &x509.Certificate{Raw: []byte("cert-a"), Subject: pkixName("device-a")}
	bb := &x509.Certificate{Raw: []byte("cert-b"), Subject: pkixName("device-b")}
	idA, err := b.bind(a); if err != nil || idA != "device-a" { t.Fatalf("bind A: %v %q", err, idA) }
	idB, err := b.bind(bb); if err != nil || idB != "device-b" { t.Fatalf("bind B: %v %q", err, idB) }
	if b.count() != 2 { t.Fatalf("device count=%d", b.count()) }
	pa, _ := b.pool("device-a"); pb, _ := b.pool("device-b")
	if pa == pb { t.Fatal("devices share the same pool") }
}

func TestBrokerRejectsCertificateSwapForExistingDevice(t *testing.T) {
	b := newBroker(1)
	first := &x509.Certificate{Raw: []byte("cert-a"), Subject: pkixName("device-a")}
	second := &x509.Certificate{Raw: []byte("different-cert"), Subject: pkixName("device-a")}
	if _, err := b.bind(first); err != nil { t.Fatal(err) }
	if _, err := b.bind(second); err == nil { t.Fatal("certificate swap accepted") }
}

func pkixName(cn string) pkix.Name { return pkix.Name{CommonName: cn} }
