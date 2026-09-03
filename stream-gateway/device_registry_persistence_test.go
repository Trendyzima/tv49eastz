package main

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"reflect"
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
	if err := r.SetPersistencePath(path); err != nil {
		t.Fatal(err)
	}
	if err := r.Register(testDevice("device-a", "fp-a")); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("registry permissions=%o, want 600", info.Mode().Perm())
	}
	reloaded := NewDeviceRegistry()
	if err := reloaded.SetPersistencePath(path); err != nil {
		t.Fatal(err)
	}
	d, ok := reloaded.Lookup("device-a")
	if !ok || d.Fingerprint != "fp-a" {
		t.Fatalf("round trip failed: %#v %v", d, ok)
	}
}

func TestDeviceRegistryPersistenceProtocolIsOrderedAndAtomic(t *testing.T) {
	r := NewDeviceRegistry()
	r.persistencePath = filepath.Join(t.TempDir(), "registry.json")

	var calls []string
	var persisted []byte
	tmp := &recordingRegistryTempFile{name: filepath.Join(filepath.Dir(r.persistencePath), ".device-registry-test.tmp"), calls: &calls}
	r.persistenceOps = registryPersistenceOps{
		createTemp: func(string, string) (registryTempFile, error) { calls = append(calls, "create-temp"); return tmp, nil },
		removeTemp: func(string) error { calls = append(calls, "remove-temp"); return nil },
		rename: func(oldPath, newPath string) error {
			calls = append(calls, "rename")
			if oldPath != tmp.name || newPath != r.persistencePath {
				t.Fatalf("rename paths: %q -> %q", oldPath, newPath)
			}
			persisted = append([]byte(nil), tmp.body...)
			return nil
		},
		syncDirectory: func(string) error { calls = append(calls, "sync-parent"); return nil },
	}

	if err := r.Register(testDevice("device-a", "fp-a")); err != nil {
		t.Fatal(err)
	}
	want := []string{"create-temp", "chmod", "write", "sync-temp", "close", "rename", "sync-parent", "remove-temp"}
	if !reflect.DeepEqual(calls, want) {
		t.Fatalf("persistence order=%v, want %v", calls, want)
	}
	if tmp.mode != 0o600 {
		t.Fatalf("temp permissions=%o, want 600", tmp.mode)
	}
	if len(persisted) == 0 {
		t.Fatal("rename published an empty registry")
	}
	if _, ok := r.Lookup("device-a"); !ok {
		t.Fatal("successful durable commit did not publish state")
	}
}

func TestDeviceRegistryPersistenceFailureBeforeRenameDoesNotPublish(t *testing.T) {
	r := NewDeviceRegistry()
	r.persistencePath = filepath.Join(t.TempDir(), "registry.json")
	var calls []string
	tmp := &recordingRegistryTempFile{name: filepath.Join(filepath.Dir(r.persistencePath), ".device-registry-test.tmp"), calls: &calls}
	r.persistenceOps = registryPersistenceOps{
		createTemp:    func(string, string) (registryTempFile, error) { calls = append(calls, "create-temp"); return tmp, nil },
		removeTemp:    func(string) error { calls = append(calls, "remove-temp"); return nil },
		rename:        func(string, string) error { calls = append(calls, "rename"); return errors.New("rename failed") },
		syncDirectory: func(string) error { calls = append(calls, "sync-parent"); return nil },
	}

	if err := r.Register(testDevice("device-a", "fp-a")); err == nil {
		t.Fatal("expected rename failure")
	}
	if _, ok := r.Lookup("device-a"); ok {
		t.Fatal("failed persistence published in-memory state")
	}
	if reflect.DeepEqual(calls, []string{"create-temp", "chmod", "write", "sync-temp", "close", "rename", "remove-temp"}) == false {
		t.Fatalf("unexpected calls=%v", calls)
	}
}

func TestDeviceRegistryPersistenceParentSyncFailureDoesNotPublish(t *testing.T) {
	r := NewDeviceRegistry()
	r.persistencePath = filepath.Join(t.TempDir(), "registry.json")
	var calls []string
	tmp := &recordingRegistryTempFile{name: filepath.Join(filepath.Dir(r.persistencePath), ".device-registry-test.tmp"), calls: &calls}
	r.persistenceOps = registryPersistenceOps{
		createTemp:    func(string, string) (registryTempFile, error) { calls = append(calls, "create-temp"); return tmp, nil },
		removeTemp:    func(string) error { calls = append(calls, "remove-temp"); return nil },
		rename:        func(string, string) error { calls = append(calls, "rename"); return nil },
		syncDirectory: func(string) error { calls = append(calls, "sync-parent"); return errors.New("parent sync failed") },
	}

	if err := r.Register(testDevice("device-a", "fp-a")); err == nil {
		t.Fatal("expected parent sync failure")
	}
	if _, ok := r.Lookup("device-a"); ok {
		t.Fatal("parent-sync failure published in-memory state")
	}
	if reflect.DeepEqual(calls, []string{"create-temp", "chmod", "write", "sync-temp", "close", "rename", "sync-parent", "remove-temp"}) == false {
		t.Fatalf("unexpected calls=%v", calls)
	}
}

type recordingRegistryTempFile struct {
	name  string
	mode  fs.FileMode
	body  []byte
	calls *[]string
}

func (f *recordingRegistryTempFile) Name() string { return f.name }
func (f *recordingRegistryTempFile) Chmod(mode fs.FileMode) error {
	f.mode = mode
	*f.calls = append(*f.calls, "chmod")
	return nil
}
func (f *recordingRegistryTempFile) Write(p []byte) (int, error) {
	*f.calls = append(*f.calls, "write")
	f.body = append(f.body[:0], p...)
	return len(p), nil
}
func (f *recordingRegistryTempFile) Sync() error {
	*f.calls = append(*f.calls, "sync-temp")
	return nil
}
func (f *recordingRegistryTempFile) Close() error { *f.calls = append(*f.calls, "close"); return nil }

func TestDeviceRegistryReplacementIsReplacementNotAppend(t *testing.T) {
	r := NewDeviceRegistry()
	d := testDevice("device-a", "fp-a")
	if err := r.Register(d); err != nil {
		t.Fatal(err)
	}
	d.PrincipalID = "principal-new"
	d.Channels = map[string]bool{"new-camera": true}
	if err := r.Replace(d); err != nil {
		t.Fatal(err)
	}
	got, ok := r.Lookup("device-a")
	if !ok || got.PrincipalID != "principal-new" || got.Channels["camera"] || !got.Channels["new-camera"] {
		t.Fatalf("replacement failed: %#v %v", got, ok)
	}
}

func TestDeviceRegistryPersistenceFailureDoesNotPublish(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "registry.json")
	r := NewDeviceRegistry()
	if err := r.SetPersistencePath(path); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(path, 0o700); err != nil {
		t.Fatal(err)
	}
	if info, err := os.Stat(path); err != nil || !info.IsDir() {
		t.Fatalf("failed to create rename blocker: %v", err)
	}
	if err := r.Register(testDevice("device-a", "fp-a")); err == nil {
		t.Fatal("expected persistence failure")
	}
	if _, ok := r.Lookup("device-a"); ok {
		t.Fatal("failed persistence published in-memory state")
	}
}

func TestDeviceRegistryMalformedStateFailsClosed(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "registry.json")
	bad := `{"version":1,"devices":[{"device_id":"device-a","principal_id":"p","fingerprint":"fp","channels":null,"enabled":true}]}`
	if err := os.WriteFile(path, []byte(bad), 0o600); err != nil {
		t.Fatal(err)
	}
	r := NewDeviceRegistry()
	if err := r.SetPersistencePath(path); err == nil {
		t.Fatal("accepted malformed registry state")
	}
	if _, ok := r.Lookup("device-a"); ok {
		t.Fatal("malformed state populated registry")
	}
}

func TestDeviceRegistryConcurrentMutations(t *testing.T) {
	r := NewDeviceRegistry()
	if err := r.Register(testDevice("device-a", "fp-a")); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			if i%2 == 0 {
				_ = r.RevokeDevice("device-a")
			} else {
				_ = r.EnableDevice("device-a")
			}
		}(i)
	}
	wg.Wait()
	if d, ok := r.Lookup("device-a"); ok && d.Fingerprint != "fp-a" {
		t.Fatalf("identity mutated: %#v", d)
	}
}
