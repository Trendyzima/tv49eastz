package main

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func testDevice(id, fp string) DeviceRecord {
	return DeviceRecord{DeviceID: id, PrincipalID: "principal-" + id, Fingerprint: fp, Channels: map[string]bool{"camera": true}, Enabled: true}
}

func TestDeviceRegistryPersistenceRoundTripAndPermissions(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "registry.json")
	r := NewDeviceRegistry()
	if err := r.SetPersistencePath(path); err != nil { t.Fatal(err) }
	if err := r.Register(testDevice("device-a", "fp-a")); err != nil { t.Fatal(err) }
	info, err := os.Stat(path)
	if err != nil { t.Fatal(err) }
	if info.Mode().Perm() != 0o600 { t.Fatalf("registry permissions=%o, want 600", info.Mode().Perm()) }
	reloaded := NewDeviceRegistry()
	if err := reloaded.SetPersistencePath(path); err != nil { t.Fatal(err) }
	d, ok := reloaded.Lookup("device-a")
	if !ok || d.Fingerprint != "fp-a" { t.Fatalf("round trip failed: %#v %v", d, ok) }
}

func TestDeviceRegistryReplacementIsReplacementNotAppend(t *testing.T) {
	r := NewDeviceRegistry()
	d := testDevice("device-a", "fp-a")
	if err := r.Register(d); err != nil { t.Fatal(err) }
	d.PrincipalID = "principal-new"
	d.Channels = map[string]bool{"new-camera": true}
	if err := r.Replace(d); err != nil { t.Fatal(err) }
	got, ok := r.Lookup("device-a")
	if !ok || got.PrincipalID != "principal-new" || got.Channels["camera"] || !got.Channels["new-camera"] { t.Fatalf("replacement failed: %#v %v", got, ok) }
}

func TestDeviceRegistryPersistenceFailureDoesNotPublish(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "registry.json")
	r := NewDeviceRegistry()
	if err := r.SetPersistencePath(path); err != nil { t.Fatal(err) }

	// Configure the path while it is absent, then turn the target itself into
	// a directory. Persistence can create its temporary file beside the target,
	// but the final atomic rename must fail when replacing this directory. The
	// test never changes the TempDir tree structure, so cleanup remains safe.
	if err := os.Mkdir(path, 0o700); err != nil { t.Fatal(err) }

	if err := r.Register(testDevice("device-a", "fp-a")); err == nil { t.Fatal("expected persistence failure") }
	if _, ok := r.Lookup("device-a"); ok { t.Fatal("failed persistence published in-memory state") }
}

func TestDeviceRegistryMalformedStateFailsClosed(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "registry.json")
	bad := `{"version":1,"devices":[{"device_id":"device-a","principal_id":"p","fingerprint":"fp","channels":null,"enabled":true}]}`
	if err := os.WriteFile(path, []byte(bad), 0o600); err != nil { t.Fatal(err) }
	r := NewDeviceRegistry()
	if err := r.SetPersistencePath(path); err == nil { t.Fatal("accepted malformed registry state") }
	if _, ok := r.Lookup("device-a"); ok { t.Fatal("malformed state populated registry") }
}

func TestDeviceRegistryConcurrentMutations(t *testing.T) {
	r := NewDeviceRegistry()
	if err := r.Register(testDevice("device-a", "fp-a")); err != nil { t.Fatal(err) }
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			if i%2 == 0 { _ = r.RevokeDevice("device-a") } else { _ = r.EnableDevice("device-a") }
		}(i)
	}
	wg.Wait()
	if d, ok := r.Lookup("device-a"); ok && d.Fingerprint != "fp-a" { t.Fatalf("identity mutated: %#v", d) }
}
