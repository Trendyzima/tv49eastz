package main

import "errors"

type AuthorizationPolicy struct {
	Registry *DeviceRegistry
}

// AuthorizeStream is the legacy device-ID policy. It is retained for callers
// that have already established identity elsewhere. Network stream entry
// points should use AuthorizeStreamWithIdentity.
func (p AuthorizationPolicy) AuthorizeStream(pr Principal, channelID, streamID string) error {
	if pr.UserID == "" || pr.DeviceID == "" || channelID == "" || streamID == "" {
		return errors.New("missing authorization subject or resource")
	}
	registry := p.Registry
	if registry == nil {
		registry = defaultDeviceRegistry
	}
	if registry == nil {
		return errors.New("device registry unavailable")
	}
	d, ok := registry.Lookup(pr.DeviceID)
	if !ok || d.PrincipalID != pr.UserID || !channelAllowed(d, channelID) {
		return errors.New("device or channel not authorized")
	}
	return nil
}

// AuthorizeStreamWithIdentity is the certificate-bound authorization path.
// The supplied fingerprint must come from a verified TLS peer certificate;
// the registry then verifies that the same certificate is bound to the
// requested device and principal before channel authorization is evaluated.
func (p AuthorizationPolicy) AuthorizeStreamWithIdentity(pr Principal, fingerprint, channelID, streamID string) error {
	if pr.UserID == "" || pr.DeviceID == "" || normalizeFingerprint(fingerprint) == "" || channelID == "" || streamID == "" {
		return errors.New("missing authorization subject, identity, or resource")
	}
	registry := p.Registry
	if registry == nil {
		registry = defaultDeviceRegistry
	}
	if registry == nil {
		return errors.New("device registry unavailable")
	}
	d, ok := registry.LookupByIdentity(pr.DeviceID, fingerprint)
	if !ok || d.PrincipalID != pr.UserID || !secureStringEqual(normalizeFingerprint(d.Fingerprint), normalizeFingerprint(fingerprint)) || !channelAllowed(d, channelID) {
		return errors.New("device, certificate, or channel not authorized")
	}
	return nil
}
