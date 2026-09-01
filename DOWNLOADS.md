# TV 49 East APK Downloads

This is the canonical download page for the Android builds produced by TV 49 East.

## Stable APKs

The production release workflow publishes these two signed APKs to every versioned GitHub Release:

| App | Package | Download |
|---|---|---|
| **FadCam** | `com.fadcam` | [Download FadCam.apk](https://github.com/Trendyzima/tv49eastz/releases/latest/download/FadCam.apk) |
| **TV 49 East** | `com.tv49.com` | [Download TV49East.apk](https://github.com/Trendyzima/tv49eastz/releases/latest/download/TV49East.apk) |

**Latest release page:** https://github.com/Trendyzima/tv49eastz/releases/latest

If no release has been published yet, the stable download links will remain unavailable until the first `v*` tag completes the production release workflow.

## What CI generates

The Android build workflow builds and uploads these test artifacts on successful CI runs:

- `FadCam-debug.apk`
- `FadCam-release-ci.apk`
- `TV49East-debug.apk`
- `TV49East-release-ci.apk`
- `SHA256SUMS.txt`
- `BUILD-INFO.txt`

These are CI/test artifacts and are not substitutes for the signed production APKs.

## Build wiring

The repository contains two Android application modules:

```text
:app          → FadCam APK
:tv-receiver  → TV 49 East APK
```

The production release workflow builds:

```text
:app:bundleDefaultRelease
:app:assembleDefaultRelease
:tv-receiver:assembleRelease
```

and publishes the resulting files as:

```text
dist/FadCam.aab
dist/FadCam.apk
dist/TV49East.apk
dist/SHA256SUMS.txt
dist/RELEASE-CERTIFICATE.txt
```

The TV receiver application ID is `com.tv49.com`.

## Verification policy

APK generation remains behind the existing Android tests, patched Media3 substitution checks, release signing checks, and production verification gates. The download page does not claim an APK is runtime-certified merely because it was built.

For FadCam → TV streaming, the repository still requires the physical-device Gate 1 sequence documented in the README before claiming end-to-end playback certification.
