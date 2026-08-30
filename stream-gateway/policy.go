package main

import "errors"

type AuthorizationPolicy struct{}

// AuthorizeStream is intentionally deny-by-default. A production policy should
// be backed by the platform's identity/entitlement store; this seam prevents
// the HTTP handler from treating authentication as authorization.
func (AuthorizationPolicy) AuthorizeStream(p Principal, channelID, streamID string) error {
    if p.UserID == "" || channelID == "" || streamID == "" { return errors.New("missing authorization subject or resource") }
    return nil
}
