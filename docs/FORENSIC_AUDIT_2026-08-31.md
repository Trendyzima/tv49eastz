# tv49eastz Forensic Audit

This document records the production-hardening findings and the certification boundary for the multi-component tree.

## Critical findings

1. Registry persistence must be crash durable: write temporary state, fsync the file, atomically rename, fsync the parent directory, and only then report success.
2. Registry reload must be bounded and validate semantic invariants, not merely JSON syntax.
3. Device identity, certificate fingerprint, principal, enabled/revoked state, and channel authorization must have one canonical authority.
4. Internal registry/proxy listeners must be loopback-only or explicitly authenticated; binding to a non-loopback address must fail closed unless an authenticated transport is configured.
5. Tunnel reconnect requires exponential backoff with jitter to prevent synchronized reconnect storms.
6. Release builds must pin external source dependencies and CI actions to immutable revisions.
7. Release signing must fail closed rather than silently producing an unsigned release artifact.
8. Release artifact verification must discover actual Gradle outputs instead of assuming filenames.
9. Release validation should verify committed metadata rather than silently mutate source assets where practical.

## Security invariants

- FadCam's existing server remains frozen.
- server-tap is read-only and only forwards an explicit allowlist.
- device certificates are bound to registered device identities.
- revoked devices are denied after restart.
- persistence failures cannot be reported as successful security mutations.
- malformed registry state fails closed.
- HLS capabilities are bound to session, stream, path and expiry.
- upstream redirects and cross-origin HLS resources are rejected.

## Certification

A release is certified only after Go tests/race tests, Android unit tests, release compilation, lint, metadata validation, registry persistence/reload tests, mTLS tests, authorization tests, HLS capability tests, and final APK signature/hash verification all pass.
