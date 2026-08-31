package main

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

type DeviceRecord struct {
	DeviceID string
	PrincipalID string
	Fingerprint string
	Channels map[string]bool
	Enabled bool
	Revoked bool
}

type DeviceRegistry struct {
	mu sync.RWMutex
	byID map[string]DeviceRecord
	byFP map[string]string
	remoteURL string
	client *http.Client
	persistencePath string
}

func NewDeviceRegistry() *DeviceRegistry {
	return &DeviceRegistry{byID: make(map[string]DeviceRecord), byFP: make(map[string]string), remoteURL: strings.TrimRight(os.Getenv("DEVICE_REGISTRY_URL"), "/"), client: &http.Client{Timeout: 3 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
}

func (r *DeviceRegistry) Register(d DeviceRecord) error {
	if err := validateDeviceRecord(d); err != nil { return err }
	if d.Channels == nil { d.Channels = make(map[string]bool) }
	r.mu.Lock(); defer r.mu.Unlock()
	if old, ok := r.byID[d.DeviceID]; ok && old.Fingerprint != d.Fingerprint { return errors.New("device identity already bound to another certificate") }
	if old, ok := r.byFP[d.Fingerprint]; ok && old != d.DeviceID { return errors.New("certificate already bound to another device") }
	next := make(map[string]DeviceRecord, len(r.byID)+1)
	for id, record := range r.byID { next[id] = record }
	next[d.DeviceID] = d
	if err := r.persistLocked(next); err != nil { return err }
	newFP := make(map[string]string, len(next))
	for id, record := range next { newFP[record.Fingerprint] = id }
	r.byID, r.byFP = next, newFP
	return nil
}

func (r *DeviceRegistry) Replace(d DeviceRecord) error { return r.Register(d) }

func (r *DeviceRegistry) RevokeDevice(deviceID string) error { return r.mutateDevice(deviceID, func(d *DeviceRecord) { d.Revoked = true; d.Enabled = false }) }
func (r *DeviceRegistry) EnableDevice(deviceID string) error { return r.mutateDevice(deviceID, func(d *DeviceRecord) { d.Revoked = false; d.Enabled = true }) }

func (r *DeviceRegistry) DeleteDevice(deviceID string) error {
	deviceID = normalizeDeviceID(deviceID); if deviceID == "" { return errors.New("device identity required") }
	r.mu.Lock(); defer r.mu.Unlock()
	if _, ok := r.byID[deviceID]; !ok { return errors.New("device not found") }
	next := make(map[string]DeviceRecord, len(r.byID)-1)
	for id, record := range r.byID { if id != deviceID { next[id] = record } }
	if err := r.persistLocked(next); err != nil { return err }
	newFP := make(map[string]string, len(next)); for id, record := range next { newFP[record.Fingerprint] = id }
	r.byID, r.byFP = next, newFP; return nil
}

func (r *DeviceRegistry) mutateDevice(deviceID string, mutate func(*DeviceRecord)) error {
	deviceID = normalizeDeviceID(deviceID); if deviceID == "" { return errors.New("device identity required") }
	r.mu.Lock(); defer r.mu.Unlock()
	d, ok := r.byID[deviceID]; if !ok { return errors.New("device not found") }
	mutate(&d)
	next := make(map[string]DeviceRecord, len(r.byID)); for id, record := range r.byID { next[id] = record }; next[deviceID] = d
	if err := r.persistLocked(next); err != nil { return err }
	newFP := make(map[string]string, len(next)); for id, record := range next { newFP[record.Fingerprint] = id }
	r.byID, r.byFP = next, newFP; return nil
}

func validateRemoteRegistryURL(raw string) (*url.URL, error) {
	u, err := url.Parse(strings.TrimSpace(raw)); if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") { return nil, errors.New("invalid device registry URL") }
	if u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.RawFragment != "" { return nil, errors.New("invalid device registry URL") }
	if u.Path != "" && u.Path != "/" { return nil, errors.New("invalid device registry URL") }; u.Path = ""; return u, nil
}

func (r *DeviceRegistry) Lookup(deviceID string) (DeviceRecord, bool) {
	deviceID = normalizeDeviceID(deviceID); if deviceID == "" { return DeviceRecord{}, false }
	if r.remoteURL != "" {
		base, err := validateRemoteRegistryURL(r.remoteURL); if err != nil { return DeviceRecord{}, false }; u := *base; u.Path = "/registry/authorize"; q := u.Query(); q.Set("device_id", deviceID); u.RawQuery = q.Encode()
		resp, err := r.client.Get(u.String()); if err != nil { return DeviceRecord{}, false }; defer resp.Body.Close(); if resp.StatusCode != http.StatusOK || !strings.HasPrefix(strings.ToLower(resp.Header.Get("Content-Type")), "application/json") { return DeviceRecord{}, false }
		const maxRegistryResponse = 64 << 10; body, err := io.ReadAll(io.LimitReader(resp.Body, maxRegistryResponse+1)); if err != nil || len(body) > maxRegistryResponse { return DeviceRecord{}, false }
		var v struct { DeviceID string `json:"device_id"`; PrincipalID string `json:"principal_id"`; Channels map[string]bool `json:"channels"`; Enabled bool `json:"enabled"`; Revoked bool `json:"revoked"` }
		if err := json.Unmarshal(body, &v); err != nil || v.Revoked || !v.Enabled || v.DeviceID != deviceID || v.PrincipalID == "" { return DeviceRecord{}, false }
		return DeviceRecord{DeviceID:v.DeviceID, PrincipalID:v.PrincipalID, Enabled:v.Enabled, Revoked:v.Revoked, Channels:v.Channels}, true
	}
	r.mu.RLock(); d, ok := r.byID[deviceID]; r.mu.RUnlock(); return d, ok && d.Enabled && !d.Revoked
}

func (r *DeviceRegistry) Verify(cert *x509.Certificate) (DeviceRecord, bool) {
	if cert == nil { return DeviceRecord{}, false }; if err := validateCertificate(cert, time.Now()); err != nil { return DeviceRecord{}, false }; fp := certificateFingerprint(cert)
	r.mu.RLock(); deviceID, ok := r.byFP[fp]; d, exists := r.byID[deviceID]; r.mu.RUnlock(); return d, ok && exists && d.Enabled && !d.Revoked
}

func certificateFingerprint(cert *x509.Certificate) string { s := sha256.Sum256(cert.Raw); return hex.EncodeToString(s[:]) }
func validateCertificate(cert *x509.Certificate, now time.Time) error { if cert == nil { return errors.New("missing certificate") }; if !cert.NotBefore.IsZero() && now.Before(cert.NotBefore) { return errors.New("certificate not yet valid") }; if !cert.NotAfter.IsZero() && !now.Before(cert.NotAfter) { return errors.New("certificate expired") }; return nil }
func channelAllowed(d DeviceRecord, channelID string) bool { return d.Enabled && !d.Revoked && channelID != "" && d.Channels[channelID] }
func normalizeDeviceID(s string) string { return strings.TrimSpace(s) }
