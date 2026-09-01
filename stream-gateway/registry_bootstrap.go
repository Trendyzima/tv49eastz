package main

import (
	"log"
	"os"
	"strings"
)

// Production startup must use durable registry state. The existing registry
// remains in-memory for tests and explicit development mode, but production
// startup fails closed when no persistence path is configured.
func init() {
	path := strings.TrimSpace(os.Getenv("DEVICE_REGISTRY_PATH"))
	production := strings.EqualFold(strings.TrimSpace(os.Getenv("PRODUCTION_MODE")), "true")
	if path == "" {
		if production {
			log.Fatal("DEVICE_REGISTRY_PATH must be configured in production")
		}
		return
	}
	if err := defaultDeviceRegistry.SetPersistencePath(path); err != nil {
		log.Fatalf("initialize device registry persistence: %v", err)
	}
}
