package main

import "errors"

type AuthorizationPolicy struct {
	Registry *DeviceRegistry
}

// AuthorizeStream is deny-by-default. A caller must name a registered,
// enabled device and that device must explicitly permit the requested channel.
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
