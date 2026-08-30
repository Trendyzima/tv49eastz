package main

import (
    "crypto/sha256"
    "crypto/subtle"
    "errors"
    "net/http"
    "strings"
    "time"
)

type Principal struct { UserID, DeviceID string }

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
    return Principal{UserID: "api-key-user", DeviceID: strings.TrimSpace(r.Header.Get("X-Device-ID"))}, nil
}

func sessionValid(s Session, now time.Time) bool {
    return !now.Before(s.IssuedAt) && now.Before(s.Expires)
}
