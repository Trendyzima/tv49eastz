package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestValidateRemoteRegistryURLRejectsAmbiguousOrigins(t *testing.T) {
	for _, raw := range []string{
		"",
		"127.0.0.1:8785",
		"ftp://127.0.0.1:8785",
		"http://user:pass@127.0.0.1:8785",
		"http://127.0.0.1:8785/registry",
		"http://127.0.0.1:8785?device_id=x",
		"http://127.0.0.1:8785#fragment",
	} {
		if _, err := validateRemoteRegistryURL(raw); err == nil {
			t.Fatalf("accepted invalid registry URL %q", raw)
		}
	}

	for _, raw := range []string{"http://127.0.0.1:8785", "https://registry.example.test"} {
		if _, err := validateRemoteRegistryURL(raw); err != nil {
			t.Fatalf("rejected valid registry URL %q: %v", raw, err)
		}
	}
}

func TestRemoteRegistryLookupFailsClosedOnWrongContentType(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"device_id":"device-a","principal_id":"p","enabled":true,"channels":{"camera":true}}`))
	}))
	defer srv.Close()

	r := &DeviceRegistry{remoteURL: srv.URL, client: srv.Client()}
	if _, ok := r.Lookup("device-a"); ok {
		t.Fatal("registry accepted non-JSON authorization response")
	}
}

func TestRemoteRegistryLookupFailsClosedOnOversizedResponse(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"device_id":"device-a","principal_id":"p","enabled":true,"channels":{"camera":true},"padding":"` + strings.Repeat("x", 70<<10) + `"}`))
	}))
	defer srv.Close()

	r := &DeviceRegistry{remoteURL: srv.URL, client: srv.Client()}
	if _, ok := r.Lookup("device-a"); ok {
		t.Fatal("registry accepted oversized authorization response")
	}
}
