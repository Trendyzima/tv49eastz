package main

// getenv is kept as a small compatibility wrapper for the stream-catalog
// relay code that is compiled together with the shield's catalog tests.
func getenv(key, fallback string) string {
	return env(key, fallback)
}
