package main

import (
	"encoding/base64"
	"errors"
	"net/http"
	"strings"
	"time"
)

type Principal struct {
	UserID   string
	DeviceID string
}

type AuthorizationPolicy struct {
	Registry *DeviceRegistry
}

var defaultDeviceRegistry = NewDeviceRegistry()

func (p AuthorizationPolicy) AuthorizeStream(principal Principal, channelID, streamID string) error {
	if principal.UserID == "" || principal.DeviceID == "" || channelID == "" || streamID == "" {
		return errors.New("incomplete stream identity")
	}
	registry := p.Registry
	if registry == nil {
		registry = defaultDeviceRegistry
	}
	d, ok := registry.Lookup(principal.DeviceID)
	if !ok || d.PrincipalID != principal.UserID || !channelAllowed(d, channelID) {
		return errors.New("stream is not authorized")
	}
	return nil
}

func authenticate(r *http.Request, expected string) (Principal, error) {
	if r == nil || expected == "" {
		return Principal{}, errors.New("missing authentication")
	}
	auth := r.Header.Get("Authorization")
	prefix := "Bearer "
	if !strings.HasPrefix(auth, prefix) || !constantTime(auth[len(prefix):], expected) {
		return Principal{}, errors.New("invalid authentication")
	}

	deviceID := strings.TrimSpace(r.Header.Get("X-Device-ID"))
	userID := strings.TrimSpace(r.Header.Get("X-Principal-ID"))
	if deviceID == "" || userID == "" {
		return Principal{}, errors.New("missing principal binding")
	}
	return Principal{UserID: userID, DeviceID: deviceID}, nil
}

func newSession(principal Principal, channelID, streamID string, ttl time.Duration) (Session, error) {
	if principal.UserID == "" || principal.DeviceID == "" || channelID == "" || streamID == "" {
		return Session{}, errors.New("invalid session binding")
	}
	if ttl <= 0 {
		return Session{}, errors.New("invalid session ttl")
	}
	now := time.Now().UTC()
	idBytes := make([]byte, 24)
	if _, err := randRead(idBytes); err != nil {
		return Session{}, err
	}
	return Session{
		ID:        base64.RawURLEncoding.EncodeToString(idBytes),
		UserID:    principal.UserID,
		DeviceID:  principal.DeviceID,
		ChannelID: channelID,
		StreamID:  streamID,
		IssuedAt:  now,
		Expires:   now.Add(ttl),
	}, nil
}

func sessionValid(s Session, now time.Time) bool {
	return s.ID != "" && s.UserID != "" && s.DeviceID != "" && s.ChannelID != "" && s.StreamID != "" && now.After(s.IssuedAt) && now.Before(s.Expires)
}

func randRead(b []byte) (int, error) {
	return cryptoRandRead(b)
}

var cryptoRandRead = func(b []byte) (int, error) {
	return rand.Read(b)
}
