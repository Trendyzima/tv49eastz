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

type Session struct {
    ID, UserID, DeviceID, ChannelID, StreamID string
    IssuedAt, ExpiresAt time.Time
}

func authenticate(r *http.Request, apiKey string) (Principal, error) {
    if apiKey == "" { return Principal{}, errors.New("authentication unavailable") }
    const prefix = "Bearer "
    h := r.Header.Get("Authorization")
    if !strings.HasPrefix(h, prefix) { return Principal{}, errors.New("missing bearer credential") }
    supplied := strings.TrimSpace(strings.TrimPrefix(h, prefix))
    if supplied == "" || subtle.ConstantTimeCompare([]byte(sha256.Sum256([]byte(supplied))[:]), []byte(sha256.Sum256([]byte(apiKey))[:])) != 1 {
        return Principal{}, errors.New("invalid credential")
    }
    // Deployment can map this principal to a real identity provider later.
    return Principal{UserID: "api-key-user", DeviceID: r.Header.Get("X-Device-ID")}, nil
}

func newSession(p Principal, channelID, streamID string, ttl time.Duration) (Session, error) {
    if p.UserID == "" || channelID == "" || streamID == "" || ttl <= 0 { return Session{}, errors.New("invalid session parameters") }
    b := make([]byte, 32); if _, err := rand.Read(b); err != nil { return Session{}, err }
    now := time.Now().UTC()
    return Session{ID: hex.EncodeToString(b), UserID:p.UserID, DeviceID:p.DeviceID, ChannelID:channelID, StreamID:streamID, IssuedAt:now, ExpiresAt:now.Add(ttl)}, nil
}

func sessionValid(s Session, now time.Time) bool { return !now.Before(s.IssuedAt) && now.Before(s.ExpiresAt) }
