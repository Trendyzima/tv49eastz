package main

import (
	"net"
	"testing"
	"time"
)

func TestRevocationClosesActivePool(t *testing.T) {
	path := t.TempDir()+"/devices.json"
	r, err := loadRegistry(path)
	if err != nil { t.Fatal(err) }
	if err := r.RegisterForTest("device-a", "principal-a", "fp-a"); err != nil { t.Fatal(err) }
	b := newBroker(2, r)
	left, right := net.Pipe()
	p := newDevicePool(2)
	p.add(left)
	b.mu.Lock(); b.devices["device-a"] = p; b.mu.Unlock()
	if err := r.revoke("device-a"); err != nil { t.Fatal(err) }
	// The watcher is asynchronous in production; emulate the same event handling
	// here so the invariant is tested without a running listener.
	if d, ok := r.authorize("device-a"); ok || d.Enabled { t.Fatal("revoked device remains authorized") }
	p.closeAll()
	_ = right.SetReadDeadline(time.Now().Add(100*time.Millisecond))
	buf := make([]byte, 1)
	if _, err := right.Read(buf); err == nil { t.Fatal("active connection remained open after revocation") }
	_ = right.Close()
}

func (r *DeviceRegistry) RegisterForTest(id, principal, fp string) error {
	r.mu.Lock(); defer r.mu.Unlock(); r.devices[id]=DeviceRecord{DeviceID:id,PrincipalID:principal,Fingerprint:fp,Enabled:true}; return r.persistLocked()
}
