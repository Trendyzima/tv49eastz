package main

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"strings"
	"sync"
	"time"
)

type DeviceRecord struct {
	DeviceID    string
	PrincipalID string
	Fingerprint string
	Channels    map[string]bool
	Enabled     bool
}

type DeviceRegistry struct {
	mu      sync.RWMutex
	byID    map[string]DeviceRecord
	byFP    map[string]string
}

func NewDeviceRegistry() *DeviceRegistry {
	return &DeviceRegistry{byID: make(map[string]DeviceRecord), byFP: make(map[string]string)}
}

func (r *DeviceRegistry) Register(d DeviceRecord) error {
	if d.DeviceID == "" || d.PrincipalID == "" || d.Fingerprint == "" {
		return errors.New("device identity fields are required")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if old, ok := r.byID[d.DeviceID]; ok && old.Fingerprint != d.Fingerprint {
		return errors.New("device identity already bound to another certificate")
	}
	if old, ok := r.byFP[d.Fingerprint]; ok && old != d.DeviceID {
		return errors.New("certificate already bound to another device")
	}
	r.byID[d.DeviceID] = d
	r.byFP[d.Fingerprint] = d.DeviceID
	return nil
}

func (r *DeviceRegistry) Verify(cert *x509.Certificate) (DeviceRecord, bool) {
	if cert == nil {
		return DeviceRecord{}, false
	}
	fp := certificateFingerprint(cert)
	r.mu.RLock()
	deviceID, ok := r.byFP[fp]
	d, exists := r.byID[deviceID]
	r.mu.RUnlock()
	return d, ok && exists && d.Enabled
}

func certificateFingerprint(cert *x509.Certificate) string {
	sum := sha256.Sum256(cert.Raw)
	return hex.EncodeToString(sum[:])
}

func validateCertificate(cert *x509.Certificate, now time.Time) error {
	if cert == nil { return errors.New("missing certificate") }
	if now.Before(cert.NotBefore) || !now.Before(cert.NotAfter) { return errors.New("certificate expired or not yet valid") }
	return nil
}

func channelAllowed(d DeviceRecord, channelID string) bool {
	if !d.Enabled || channelID == "" { return false }
	return d.Channels[channelID]
}

func normalizeDeviceID(s string) string { return strings.TrimSpace(s) }
