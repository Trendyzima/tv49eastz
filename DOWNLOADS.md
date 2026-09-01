# TV 49 East APK Downloads

This is the canonical download page for the Android builds produced by TV 49 East.

## 📱 Latest APKs

The `master` Android CI workflow now builds both applications and publishes a **Latest CI Build** GitHub Release containing real APK assets. That means these links point to actual files rather than an empty/nonexistent release:

| App | Package | Download |
|---|---|---|
| **FadCam** | `com.fadcam` | [Download latest FadCam APK](https://github.com/Trendyzima/tv49eastz/releases/download/latest/FadCam.apk) |
| **TV 49 East** | `com.tv49.com` | [Download latest TV 49 East APK](https://github.com/Trendyzima/tv49eastz/releases/download/latest/TV49East.apk) |

**Latest CI release:** https://github.com/Trendyzima/tv49eastz/releases/tag/latest

These `latest` APKs are **CI-signed testing builds**, not production-signed builds. They are generated from the current `master` commit after the Android tests and APK build succeed.

## 🔐 Production APKs

Versioned `v*` releases are produced by the separate production-release workflow. That workflow requires the repository's production signing secrets, runs its verification gates, signs the APKs, generates provenance/checksums, and publishes:

```text
dist/FadCam.apk
dist/TV49East.apk
dist/FadCam.aab
dist/SHA256SUMS.txt
dist/RELEASE-CERTIFICATE.txt
```

Once a production versioned release exists, its `FadCam.apk` and `TV49East.apk` are the production downloads. The `/releases/latest` page will then point to the newest eligible release according to GitHub's release ordering.

**Production releases:** https://github.com/Trendyzima/tv49eastz/releases

## What CI generates

Every successful Android CI build also uploads a workflow artifact containing:

- `FadCam-debug.apk`
- `FadCam-release-ci.apk`
- `TV49East-debug.apk`
- `TV49East-release-ci.apk`
- `SHA256SUMS.txt`
- `BUILD-INFO.txt`

The GitHub Release additionally exposes stable asset names `FadCam.apk` and `TV49East.apk` for the latest CI build so users do not need to open the Actions artifact ZIP.

## Build wiring

The repository contains two Android application modules:

```text
:app          → FadCam APK (`com.fadcam`)
:tv-receiver  → TV 49 East APK (`com.tv49.com`)
```

The Android CI workflow explicitly runs:

```text
:app:assembleDefaultDebug
:app:assembleDefaultRelease
:tv-receiver:assembleDebug
:tv-receiver:assembleRelease
```

It then fails closed if any of the four expected APKs is missing or empty before publishing the CI release assets.

The production release workflow explicitly runs:

```text
:app:bundleDefaultRelease
:app:assembleDefaultRelease
:tv-receiver:assembleRelease
```

and validates the resulting production APK/AAB files before publishing them.

## Verification policy

An APK being downloadable proves that the build pipeline produced a file; it does **not** by itself certify FadCam → TV end-to-end playback. Runtime certification still requires the physical-device Gate 1 sequence documented in the README.
