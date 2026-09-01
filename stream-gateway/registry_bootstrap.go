package main

import (
	"log"
	"os"
	"strings"
)

// Production startup must use durable registry state and the same device
// authorization source as the device-tunnel broker. The existing registry
// remains in-memory for tests and explicit development mode, but production
// startup fails closed when either half of the device/tunnel wiring is absent.
func init() {
	path := strings.TrimSpace(os.Getenv("DEVICE_REGISTRY_PATH"))
	production := strings.EqualFold(strings.TrimSpace(os.Getenv("PRODUCTION_MODE")), "true")
	if production {
		if path == "" {
			log.Fatal("DEVICE_REGISTRY_PATH must be configured in production")
		}
		if strings.TrimSpace(os.Getenv("DEVICE_REGISTRY_URL")) == "" {
			log.Fatal("DEVICE_REGISTRY_URL must point to the device-tunnel registry in production")
		}
		if strings.TrimSpace(os.Getenv("TUNNEL_PROXY_BASE_URL")) == "" {
			log.Fatal("TUNNEL_PROXY_BASE_URL must point to the local device-tunnel proxy in production")
		}
	}
	if path == "" {
		return
	}
	if err := defaultDeviceRegistry.SetPersistencePath(path); err != nil {
		log.Fatalf("initialize device registry persistence: %v", err)
	}
}
