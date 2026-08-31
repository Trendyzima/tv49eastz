package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"encoding/base64"
	"errors"
	"net/http"
	"strings"
	"time"
)

type Principal struct {
	UserID      string
	DeviceID    string
	Fingerprint string
}

// The API credential authenticates the caller. Device ownership is established
// separately from the verified TLS certificate; X-Device-ID is only a selector
// and is never proof of device ownership.
func authenticate(r *http.Request, apiKey string) (Principal, error) {
	if apiKey == "" { return Principal{}, errors.New("authentication unavailable") }
	const prefix = "Bearer "
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, prefix) { return Principal{}, errors.New("missing bearer credential") }
	supplied := strings.TrimSpace(strings.TrimPrefix(h, prefix))
	if supplied == "" { return Principal{}, errors.New("empty credential") }
	a := sha256.Sum256([]byte(supplied)); b := sha256.Sum256([]byte(apiKey))
	if subtle.ConstantTimeCompare(a[:], b[:]) != 1 { return Principal{}, errors.New("invalid credential") }
	deviceID := normalizeDeviceID(r.Header.Get("X-Device-ID"))
	if deviceID == "" { return Principal{}, errors.New("device identity required") }
	return Principal{UserID: "api-key-user", DeviceID: deviceID}, nil
}

// AuthenticateTLS establishes the complete trust chain. The TLS peer
// certificate supplies DeviceID, PrincipalID and fingerprint; the HTTP device
// header, when present, can only be a consistency check.
func AuthenticateTLS(r *http.Request, state *tls.ConnectionState, apiKey string, registry *DeviceRegistry) (Principal, error) {
	if r == nil { return Principal{}, errors.New("request unavailable") }
	if apiKey == "" { return Principal{}, errors.New("authentication unavailable") }
	const prefix = "Bearer "
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, prefix) { return Principal{}, errors.New("missing bearer credential") }
	supplied := strings.TrimSpace(strings.TrimPrefix(h, prefix))
	if supplied == "" { return Principal{}, errors.New("empty credential") }
	a := sha256.Sum256([]byte(supplied)); b := sha256.Sum256([]byte(apiKey))
	if subtle.ConstantTimeCompare(a[:], b[:]) != 1 { return Principal{}, errors.New("invalid credential") }
	d, err := DeviceIdentityFromTLS(state, registry)
	if err != nil { return Principal{}, err }
	if headerID := normalizeDeviceID(r.Header.Get("X-Device-ID")); headerID != "" && headerID != d.DeviceID { return Principal{}, errors.New("device identity header does not match certificate") }
	return Principal{UserID: d.PrincipalID, DeviceID: d.DeviceID, Fingerprint: normalizeFingerprint(d.Fingerprint)}, nil
}

func newSession(principal Principal, channelID, streamID string, ttl time.Duration) (Session, error) {
	if principal.UserID == "" || principal.DeviceID == "" || channelID == "" || streamID == "" { return Session{}, errors.New("invalid session binding") }
	if ttl <= 0 { return Session{}, errors.New("invalid session ttl") }
	id := make([]byte, 24); if _, err := rand.Read(id); err != nil { return Session{}, err }
	now := time.Now().UTC()
	return Session{ID: base64.RawURLEncoding.EncodeToString(id), UserID: principal.UserID, DeviceID: principal.DeviceID, ChannelID: channelID, StreamID: streamID, IssuedAt: now, Expires: now.Add(ttl)}, nil
}

func sessionValid(s Session, now time.Time) bool { return !now.Before(s.IssuedAt) && now.Before(s.Expires) }
