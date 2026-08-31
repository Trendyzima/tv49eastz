package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

const deviceRegistryFileMode fs.FileMode = 0o600

type persistedDeviceRegistry struct {
	Version int            `json:"version"`
	Devices []DeviceRecord `json:"devices"`
}

func (r *DeviceRegistry) SetPersistencePath(path string) error {
	path = strings.TrimSpace(path)
	if path == "" {
		return errors.New("empty device registry persistence path")
	}
	if !filepath.IsAbs(path) {
		return errors.New("device registry persistence path must be absolute")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.persistencePath = path
	return r.loadLocked(path)
}

func (r *DeviceRegistry) Reload() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.persistencePath == "" {
		return errors.New("device registry persistence is not configured")
	}
	return r.loadLocked(r.persistencePath)
}

func (r *DeviceRegistry) loadLocked(path string) error {
	body, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return fmt.Errorf("read device registry: %w", err)
	}
	var state persistedDeviceRegistry
	if err := json.Unmarshal(body, &state); err != nil {
		return fmt.Errorf("decode device registry: %w", err)
	}
	if state.Version != 1 || state.Devices == nil {
		return errors.New("invalid device registry state")
	}

	byID := make(map[string]DeviceRecord, len(state.Devices))
	byFP := make(map[string]string, len(state.Devices))
	for _, d := range state.Devices {
		if err := validateDeviceRecord(d); err != nil {
			return fmt.Errorf("invalid device registry entry: %w", err)
		}
		if _, exists := byID[d.DeviceID]; exists {
			return errors.New("duplicate device id in registry")
		}
		if owner, exists := byFP[d.Fingerprint]; exists && owner != d.DeviceID {
			return errors.New("duplicate certificate fingerprint in registry")
		}
		d.Channels = cloneChannels(d.Channels)
		byID[d.DeviceID] = d
		byFP[d.Fingerprint] = d.DeviceID
	}

	// Only publish a fully validated state. A malformed file therefore cannot
	// partially replace a working in-memory registry.
	r.byID = byID
	r.byFP = byFP
	return nil
}

func validateDeviceRecord(d DeviceRecord) error {
	if normalizeDeviceID(d.DeviceID) != d.DeviceID || d.DeviceID == "" {
		return errors.New("invalid device id")
	}
	if strings.TrimSpace(d.PrincipalID) == "" || strings.TrimSpace(d.Fingerprint) == "" {
		return errors.New("device identity fields are required")
	}
	if d.Channels == nil {
		return errors.New("channels must be present")
	}
	return nil
}

func cloneChannels(in map[string]bool) map[string]bool {
	out := make(map[string]bool, len(in))
	for k, v := range in {
		out[k] = v
	}
	return out
}

func cloneRecords(in map[string]DeviceRecord) []DeviceRecord {
	ids := make([]string, 0, len(in))
	for id := range in {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	out := make([]DeviceRecord, 0, len(ids))
	for _, id := range ids {
		d := in[id]
		d.Channels = cloneChannels(d.Channels)
		out = append(out, d)
	}
	return out
}

func (r *DeviceRegistry) persistLocked(byID map[string]DeviceRecord) error {
	if r.persistencePath == "" {
		return nil
	}
	state := persistedDeviceRegistry{Version: 1, Devices: cloneRecords(byID)}
	body, err := json.Marshal(state)
	if err != nil {
		return fmt.Errorf("encode device registry: %w", err)
	}

	dir := filepath.Dir(r.persistencePath)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("create registry directory: %w", err)
	}
	tmp, err := os.CreateTemp(dir, ".device-registry-*.tmp")
	if err != nil {
		return fmt.Errorf("create registry temp file: %w", err)
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)
	if err := tmp.Chmod(deviceRegistryFileMode); err != nil {
		tmp.Close()
		return fmt.Errorf("set registry temp permissions: %w", err)
	}
	if _, err := tmp.Write(body); err != nil {
		tmp.Close()
		return fmt.Errorf("write registry: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return fmt.Errorf("sync registry: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close registry temp file: %w", err)
	}
	if err := os.Chmod(tmpName, deviceRegistryFileMode); err != nil {
		return fmt.Errorf("set registry permissions: %w", err)
	}
	if err := os.Rename(tmpName, r.persistencePath); err != nil {
		return fmt.Errorf("replace registry: %w", err)
	}
	if err := syncDirectory(dir); err != nil {
		return fmt.Errorf("sync registry directory: %w", err)
	}
	return nil
}

func syncDirectory(path string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}
