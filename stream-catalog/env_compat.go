package main

// getenv preserves the helper name used by the relay implementation.
// env is the canonical configuration helper in main.go.
func getenv(key, fallback string) string {
	return env(key, fallback)
}
