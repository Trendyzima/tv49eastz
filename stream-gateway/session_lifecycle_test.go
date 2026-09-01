package main

import (
	"net/http"
	"net/http/httptest"
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
		ID: "session-1", UserID: "user-1", DeviceID: "device-1", Fingerprint: "fingerprint-1",
		ChannelID: "camera", StreamID: "stream-1", IssuedAt: time.Now().UTC(), Expires: time.Now().UTC().Add(time.Minute),
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
	req := httptest.NewRequest(http.MethodPost, "/v1/session", strings.NewReader(`{"channel_id":"camera","stream_id":"abc"}`))
	channel, stream, err := decodeSessionRequest(req)
	if err != nil || channel != "camera" || stream != "abc" {
		t.Fatalf("decode=%q,%q,%v", channel, stream, err)
	}
}

func TestSessionMiddlewareRequiresMutationMethods(t *testing.T) {
	g := &Gateway{cfg: Config{APIKey: "test", MaxSessions: 1}}
	h := g.middleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	for _, tc := range []struct {
		method, path string
		want         int
	}{
		{http.MethodGet, "/v1/session", http.StatusMethodNotAllowed},
		{http.MethodPost, "/v1/session/abc", http.StatusMethodNotAllowed},
		{http.MethodPost, "/v1/session", http.StatusUnauthorized},
		{http.MethodDelete, "/v1/session/abc", http.StatusUnauthorized},
	} {
		r := httptest.NewRequest(tc.method, tc.path, nil)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != tc.want {
			t.Fatalf("%s %s=%d, want %d", tc.method, tc.path, w.Code, tc.want)
		}
	}
}
