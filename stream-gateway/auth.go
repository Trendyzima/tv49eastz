package main

import (
    "crypto/rand"
    "crypto/sha256"
    "crypto/subtle"
    "encoding/hex"
    "errors"
    "net/http"
    "strings"
    "time"
)

type Principal struct { UserID, DeviceID string }

type AuthSession struct {
    ID, UserID, DeviceID, ChannelID, StreamID string
    IssuedAt, ExpiresAt time.Time
}

func authenticate(r *http.Request, apiKey string) (Principal, error) {
    if apiKey == "" { return Principal{}, errors.New("authentication unavailable") }
    const prefix = "Bearer "
    h := r.Header.Get("Authorization")
    if !strings.HasPrefix(h, prefix) { return Principal{}, errors.New("missing bearer credential") }
    supplied := strings.TrimSpace(strings.TrimPrefix(h, prefix))
    if supplied == "" { return Principal{}, errors.New("empty credential") }
    a := sha256.Sum256([]byte(supplied))
    b := sha256.Sum256([]byte(apiKey))
    if subtle.ConstantTimeCompare(a[:], b[:]) != 1 { return Principal{}, errors.New("invalid credential") }
    deviceID := strings.TrimSpace(r.Header.Get("X-Device-ID"))
    return Principal{UserID: "api-key-user", DeviceID: deviceID}, nil
}

func newAuthSession(p Principal, channelID, streamID string, ttl time.Duration) (AuthSession, error) {
    if p.UserID == "" || channelID == "" || streamID == "" || ttl <= 0 { return AuthSession{}, errors.New("invalid session parameters") }
    b := make([]byte, 32)
    if _, err := rand.Read(b); err != nil { return AuthSession{}, err }
    now := time.Now().UTC()
    return AuthSession{ID: hex.EncodeToString(b), UserID:p.UserID, DeviceID:p.DeviceID, ChannelID:channelID, StreamID:streamID, IssuedAt:now, ExpiresAt:now.Add(ttl)}, nil
}

func authSessionValid(s AuthSession, now time.Time) bool { return !now.Before(s.IssuedAt) && now.Before(s.ExpiresAt) }
