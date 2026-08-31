# TV 49 East APK Pair

## Application identities

| APK | Application ID | Role |
|---|---|---|
| FadCam | `com.fadcam` | FadCam publisher/source application |
| TV 49 East | `com.tv49.com` | Standalone TV receiver |

`com.tv49.com` is the canonical package/application ID for the TV receiver release APK.

## FadCam → TV handoff

The integration has one dedicated sender: `FadCamTvPublisher`.

The handoff is versioned as protocol `v=1` and contains:

- publisher package
- random nonce
- issue timestamp
- 60-second expiry
- HTTPS stream URL
- display name/owner
- per-install Android Keystore EC public key
- ECDSA/SHA-256 signature

Canonical signing input is deterministic and the receiver verifies the signature before playback.

## Caller authentication

The receiver exposes a dedicated `FadCamHandoffActivity` for `fadcam://stream`.

It is protected by:

`com.tv49.com.permission.PUBLISH_FADCAM`

with Android `signature` protection. The FadCam APK requests that permission.

The release certification workflow signs both APKs with the same CI release certificate and fails if their APK certificate SHA-256 fingerprints differ. This makes the Android package signature the first caller-authentication boundary; the signed capability is the second boundary.

The receiver also rejects:

- unexpected publisher package IDs
- missing capability fields
- non-HTTPS stream URLs
- expired capabilities
- capabilities with excessive lifetime
- replayed nonces
- invalid public keys
- invalid signatures

## Signing certificates

### Release

CI creates an ephemeral RSA-2048 certificate for each certification run and signs both release APKs with that same keystore. The exact SHA-256 fingerprint is written to the downloadable `PAIR-MANIFEST.txt` and `signing-certificate.txt` artifacts.

The CI certificate is deliberately **not** committed to the repository.

For a local production pair, both APKs must be signed with the same release keystore if signature-level caller authentication is required.

### Debug

Debug APKs use the normal Android debug signing configuration. They are for development/testing and must not be treated as the production trust identity.

## FadCam streaming owner

The FadCam streaming path is owned by `RemoteStreamService`, which manages the `LiveM3U8Server` lifecycle and local streaming port. The publisher is intentionally kept separate from that service/UI logic so future gateway publication can be wired through one security-reviewed client instead of scattering `Intent` construction across the application.

## Certification

The Android Build Certification workflow produces exactly two named release artifacts:

- `FadCam.apk`
- `TV49East.apk`

It also verifies package IDs, APK alignment, and matching release certificate fingerprints before upload.

## Security rule

`fadcam://stream` is a transport/bootstrap mechanism, not an authentication mechanism by itself. Caller authentication comes from the signature-level Android permission and package-signature match; content authentication comes from the signed, expiring capability.
