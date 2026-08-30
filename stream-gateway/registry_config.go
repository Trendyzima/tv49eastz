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
// device. For production this loader can be replaced by a database-backed
// registry without changing authorization call sites.
func init() {
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
