package main

import (
	"crypto/x509"
	"crypto/x509/pkix"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestRegistryPersistsAndRevokes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "devices.json")
	reg, err := loadRegistry(path)
	if err != nil { t.Fatal(err) }
	cert := &x509.Certificate{Raw: []byte("cert-a"), Subject: pkix.Name{CommonName: "device-a"}}
	fp := fingerprint(cert)
	reg.mu.Lock()
	reg.devices["device-a"] = DeviceRecord{DeviceID: "device-a", PrincipalID: "p-a", Fingerprint: fp, Enabled: true}
	err = reg.persistLocked()
	reg.mu.Unlock()
	if err != nil { t.Fatal(err) }
	if _, err := os.Stat(path); err != nil { t.Fatal(err) }
	reloaded, err := loadRegistry(path)
	if err != nil { t.Fatal(err) }
	if d, ok := reloaded.authorize("device-a"); !ok || d.PrincipalID != "p-a" { t.Fatal("persisted device not loaded") }
	now := time.Now().UTC()
	reloaded.mu.Lock()
	d := reloaded.devices["device-a"]
	d.RevokedAt = &now
	reloaded.devices["device-a"] = d
	err = reloaded.persistLocked()
	reloaded.mu.Unlock()
	if err != nil { t.Fatal(err) }
	reloaded2, err := loadRegistry(path)
	if err != nil { t.Fatal(err) }
	if _, ok := reloaded2.authorize("device-a"); ok { t.Fatal("revoked device authorized") }
}

func TestRegistryRejectsWrongCertificate(t *testing.T) {
	reg := &DeviceRegistry{devices: map[string]DeviceRecord{
		"device-a": {DeviceID: "device-a", Fingerprint: fingerprint(&x509.Certificate{Raw: []byte("cert-a")}), Enabled: true},
	}}
	_, err := reg.bind(&x509.Certificate{Raw: []byte("cert-b"), Subject: pkix.Name{CommonName: "device-a"}})
	if err == nil { t.Fatal("certificate replacement accepted") }
}
