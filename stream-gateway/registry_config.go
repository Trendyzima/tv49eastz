package main

import (
	"encoding/json"
	"errors"
	"log"
	"os"
	"strings"
)

var defaultDeviceRegistry = NewDeviceRegistry()

// DEVICE_REGISTRY_JSON is an array of DeviceRecord objects. Keeping the
// registry outside request data prevents a client from self-registering a
// device. DEVICE_REGISTRY_FILE enables crash-durable local registry state.
func init() {
	if path := strings.TrimSpace(os.Getenv("DEVICE_REGISTRY_FILE")); path != "" {
		if err := defaultDeviceRegistry.SetPersistencePath(path); err != nil {
			log.Fatalf("invalid DEVICE_REGISTRY_FILE: %v", err)
		}
	}
	raw := strings.TrimSpace(os.Getenv("DEVICE_REGISTRY_JSON"))
	if raw == "" {
		return
	}
	var records []DeviceRecord
	if err := json.Unmarshal([]byte(raw), &records); err != nil {
		log.Fatalf("invalid DEVICE_REGISTRY_JSON: %v", err)
	}
	for _, record := range records {
		if err := defaultDeviceRegistry.Register(record); err != nil {
			log.Fatalf("invalid device registry entry: %v", err)
		}
	}
}

func registryForPolicy() (*DeviceRegistry, error) {
	if defaultDeviceRegistry == nil {
		return nil, errors.New("device registry unavailable")
	}
	return defaultDeviceRegistry, nil
}
