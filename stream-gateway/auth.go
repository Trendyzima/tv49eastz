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

// authenticate is the session authentication entry point. API credentials
// authenticate the caller, but device ownership is always established from
// the verified TLS peer certificate. X-Device-ID is never an authority source.
func authenticate(r *http.Request, apiKey string) (Principal, error) {
	return authenticateWithRegistry(r, apiKey, defaultDeviceRegistry)
}

// authenticateWithRegistry composes caller authentication with certificate
// identity. Keeping the registry explicit makes the security boundary testable
// and prevents callers from accidentally falling back to header-derived
// device identity.
func authenticateWithRegistry(r *http.Request, apiKey string, registry *DeviceRegistry) (Principal, error) {
	if r == nil {
		return Principal{}, errors.New("request unavailable")
	}
	if apiKey == "" {
		return Principal{}, errors.New("authentication unavailable")
	}
	const prefix = "Bearer "
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, prefix) {
		return Principal{}, errors.New("missing bearer credential")
	}
	supplied := strings.TrimSpace(strings.TrimPrefix(h, prefix))
	if supplied == "" {
		return Principal{}, errors.New("empty credential")
	}
	a := sha256.Sum256([]byte(supplied))
	b := sha256.Sum256([]byte(apiKey))
	if subtle.ConstantTimeCompare(a[:], b[:]) != 1 {
		return Principal{}, errors.New("invalid credential")
	}

	// There is deliberately no header-only fallback. A session is a
	// device-bound security object, so a verified TLS client certificate is
	// mandatory at the session boundary.
	if r.TLS == nil {
		return Principal{}, errors.New("verified device TLS identity required")
	}
	return authenticateTLSWithRegistry(r, r.TLS, registry)
}

// AuthenticateTLS establishes the complete trust chain. The TLS peer
// certificate supplies DeviceID, PrincipalID and fingerprint; the HTTP device
// header, when present, can only be a consistency check.
func AuthenticateTLS(r *http.Request, state *tls.ConnectionState, apiKey string, registry *DeviceRegistry) (Principal, error) {
	if r == nil {
		return Principal{}, errors.New("request unavailable")
	}
	if apiKey == "" {
		return Principal{}, errors.New("authentication unavailable")
	}
	const prefix = "Bearer "
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, prefix) {
		return Principal{}, errors.New("missing bearer credential")
	}
	supplied := strings.TrimSpace(strings.TrimPrefix(h, prefix))
	if supplied == "" {
		return Principal{}, errors.New("empty credential")
	}
	a := sha256.Sum256([]byte(supplied))
	b := sha256.Sum256([]byte(apiKey))
	if subtle.ConstantTimeCompare(a[:], b[:]) != 1 {
		return Principal{}, errors.New("invalid credential")
	}
	return authenticateTLSWithRegistry(r, state, registry)
}

func authenticateTLSWithRegistry(r *http.Request, state *tls.ConnectionState, registry *DeviceRegistry) (Principal, error) {
	d, err := DeviceIdentityFromTLS(state, registry)
	if err != nil {
		return Principal{}, err
	}
	if headerID := normalizeDeviceID(r.Header.Get("X-Device-ID")); headerID != "" && headerID != d.DeviceID {
		return Principal{}, errors.New("device identity header does not match certificate")
	}
	return Principal{UserID: d.PrincipalID, DeviceID: d.DeviceID, Fingerprint: normalizeFingerprint(d.Fingerprint)}, nil
}

func newSession(principal Principal, channelID, streamID string, ttl time.Duration) (Session, error) {
	if principal.UserID == "" || principal.DeviceID == "" || channelID == "" || streamID == "" {
		return Session{}, errors.New("invalid session binding")
	}
	if ttl <= 0 {
		return Session{}, errors.New("invalid session ttl")
	}
	id := make([]byte, 24)
	if _, err := rand.Read(id); err != nil {
		return Session{}, err
	}
	now := time.Now().UTC()
	return Session{ID: base64.RawURLEncoding.EncodeToString(id), UserID: principal.UserID, DeviceID: principal.DeviceID, ChannelID: channelID, StreamID: streamID, IssuedAt: now, Expires: now.Add(ttl)}, nil
}

func sessionValid(s Session, now time.Time) bool {
	return !now.Before(s.IssuedAt) && now.Before(s.Expires)
}
