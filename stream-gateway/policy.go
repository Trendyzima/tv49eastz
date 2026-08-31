package main

import "errors"

type AuthorizationPolicy struct {
	Registry *DeviceRegistry
}

// AuthorizeStream is the gateway session authorization entry point.
// It is intentionally certificate-bound: a network session may not be
// authorized from DeviceID alone. The Principal fingerprint must have been
// derived from a verified TLS peer certificate by the authentication layer.
func (p AuthorizationPolicy) AuthorizeStream(pr Principal, channelID, streamID string) error {
	if pr.Fingerprint == "" {
		return errors.New("certificate identity required")
	}
	return p.AuthorizeStreamWithIdentity(pr, pr.Fingerprint, channelID, streamID)
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
