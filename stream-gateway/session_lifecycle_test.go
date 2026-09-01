package main

import (
    "strings"
    "testing"
    "time"
)

func TestSessionSlotAccountingAndRevoke(t *testing.T) {
    g := &Gateway{cfg: Config{MaxSessions: 1}}
    if !g.reserveSessionSlot() {
        t.Fatal("first slot reservation failed")
    }
    if g.reserveSessionSlot() {
        t.Fatal("second slot reservation unexpectedly succeeded")
    }

    s := Session{
        ID:          "session-1",
        UserID:      "user-1",
        DeviceID:    "device-1",
        Fingerprint: "fingerprint-1",
        ChannelID:   "camera",
        StreamID:    "stream-1",
        IssuedAt:    time.Now().UTC(),
        Expires:     time.Now().UTC().Add(time.Minute),
    }
    g.sessions.Store(s.ID, s)

    if !g.revokeSession(s.ID) {
        t.Fatal("session was not revoked")
    }
    if _, ok := g.sessions.Load(s.ID); ok {
        t.Fatal("revoked session still present")
    }
    if g.sessionCount.Load() != 0 {
        t.Fatalf("session count=%d, want 0", g.sessionCount.Load())
    }
    if g.revokeSession(s.ID) {
        t.Fatal("second revoke should be idempotent/no-op")
    }
}

func TestDecodeSessionRequest(t *testing.T) {
    r := strings.NewReader(`{"channel_id":"camera","stream_id":"abc"}`)
    req := newRequestWithBody(r)
    channel, stream, err := decodeSessionRequest(req)
    if err != nil || channel != "camera" || stream != "abc" {
        t.Fatalf("decode=%q,%q,%v", channel, stream, err)
    }
}

func TestSessionMiddlewareRequiresMutationMethods(t *testing.T) {
    g := &Gateway{cfg: Config{APIKey: "test", MaxSessions: 1}}
    h := g.middleware(nil)
    for _, tc := range []struct {
        method string
        path   string
        want   int
    }{
        {"GET", "/v1/session", 405},
        {"POST", "/v1/session/abc", 405},
        {"POST", "/v1/session", 401},
        {"DELETE", "/v1/session/abc", 401},
    } {
        r := newRequest(methodPath(tc.method, tc.path), nil)
        w := newRecorder()
        h.ServeHTTP(w, r)
        if w.code != tc.want {
            t.Fatalf("%s %s=%d, want %d", tc.method, tc.path, w.code, tc.want)
        }
    }
}
