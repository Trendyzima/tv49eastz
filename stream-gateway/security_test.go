package main

import (
    "net/http/httptest"
    "testing"
    "time"
)

func TestSessionIsBoundAndExpires(t *testing.T) {
    p := Principal{UserID:"u1", DeviceID:"d1"}
    s, err := newSession(p, "ch1", "stream1", time.Minute)
    if err != nil { t.Fatal(err) }
    if s.UserID != "u1" || s.DeviceID != "d1" || s.ChannelID != "ch1" || s.StreamID != "stream1" { t.Fatal("session is not fully bound") }
    if !sessionValid(s, s.IssuedAt.Add(time.Second)) { t.Fatal("fresh session should be valid") }
    if sessionValid(s, s.ExpiresAt) { t.Fatal("expired session must be rejected") }
}

func TestAuthenticateRejectsMissingCredential(t *testing.T) {
    r := httptest.NewRequest("GET", "/", nil)
    if _, err := authenticate(r, "secret"); err == nil { t.Fatal("missing credential accepted") }
}

func TestAuthenticateRejectsWrongCredential(t *testing.T) {
    r := httptest.NewRequest("GET", "/", nil)
    r.Header.Set("Authorization", "Bearer wrong")
    if _, err := authenticate(r, "secret"); err == nil { t.Fatal("wrong credential accepted") }
}
