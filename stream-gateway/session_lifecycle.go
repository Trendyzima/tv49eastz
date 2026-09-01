package main

import (
    "encoding/json"
    "errors"
    "net/http"
    "strings"
    "sync/atomic"
)

// reserveSessionSlot atomically enforces the configured concurrent-session cap.
// A failed reservation never increments the active count.
func (g *Gateway) reserveSessionSlot() bool {
    max := int64(g.cfg.MaxSessions)
    if max <= 0 {
        max = 25
    }
    for {
        current := g.sessionCount.Load()
        if current >= max {
            return false
        }
        if g.sessionCount.CompareAndSwap(current, current+1) {
            return true
        }
    }
}

func (g *Gateway) releaseSessionSlot() {
    for {
        current := g.sessionCount.Load()
        if current <= 0 {
            return
        }
        if g.sessionCount.CompareAndSwap(current, current-1) {
            return
        }
    }
}

// revokeSession removes a live session exactly once. CompareAndDelete makes
// repeated Stop-TV requests idempotent and prevents double slot release.
func (g *Gateway) revokeSession(id string) bool {
    id = strings.TrimSpace(id)
    if id == "" {
        return false
    }
    value, ok := g.sessions.Load(id)
    if !ok {
        return false
    }
    session, ok := value.(Session)
    if !ok {
        return false
    }
    if !g.sessions.CompareAndDelete(id, session) {
        return false
    }
    g.releaseSessionSlot()
    return true
}

// revokeSessionHandler is deliberately authenticated with the same verified
// API + mTLS device identity as session creation. The caller can only revoke
// its own device's session, preventing one device from terminating another.
func (g *Gateway) revokeSessionHandler(w http.ResponseWriter, r *http.Request) {
    principal, err := authenticate(r, g.cfg.APIKey)
    if err != nil {
        http.Error(w, "unauthorized", http.StatusUnauthorized)
        return
    }
    id := strings.TrimPrefix(r.URL.Path, "/v1/session/")
    id = strings.TrimSpace(strings.TrimSuffix(id, "/"))
    if id == "" || strings.Contains(id, "/") {
        http.Error(w, "invalid session", http.StatusBadRequest)
        return
    }

    value, ok := g.sessions.Load(id)
    if !ok {
        w.WriteHeader(http.StatusNoContent)
        return
    }
    session, ok := value.(Session)
    if !ok {
        http.Error(w, "invalid session", http.StatusInternalServerError)
        return
    }
    if session.UserID != principal.UserID || session.DeviceID != principal.DeviceID || session.Fingerprint != principal.Fingerprint {
        http.Error(w, "forbidden", http.StatusForbidden)
        return
    }

    g.revokeSession(id)
    w.WriteHeader(http.StatusNoContent)
}

// decodeSessionRequest accepts only a small JSON object for POST session
// creation. Keeping this separate makes the mutation contract explicit.
type sessionRequest struct {
    ChannelID string `json:"channel_id"`
    StreamID  string `json:"stream_id"`
}

func decodeSessionRequest(r *http.Request) (string, string, error) {
    if r == nil || r.Body == nil {
        return "", "", errors.New("request body required")
    }
    defer r.Body.Close()
    var req sessionRequest
    dec := json.NewDecoder(r.Body)
    dec.DisallowUnknownFields()
    if err := dec.Decode(&req); err != nil {
        return "", "", errors.New("invalid JSON body")
    }
    channelID := strings.TrimSpace(req.ChannelID)
    streamID := strings.TrimSpace(req.StreamID)
    if channelID == "" || streamID == "" {
        return "", "", errors.New("channel_id and stream_id are required")
    }
    return channelID, streamID, nil
}

var _ atomic.Int64
