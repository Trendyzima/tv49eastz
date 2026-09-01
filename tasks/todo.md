# APK download and build integration

- [x] Audit the Android modules and existing APK workflows.
- [x] Confirm the FadCam module and TV receiver module have APK-producing Gradle tasks.
- [x] Confirm the TV receiver application ID is `com.tv49.com`.
- [x] Add a dedicated APK download page/documentation with stable release links.
- [x] Add README navigation to the APK download page and direct latest-release APK links.
- [x] Keep CI APK artifacts and production release APK assets aligned with the documented filenames.
- [x] Make the verified Android certification workflow publish the two APKs as direct GitHub Release assets.
- [x] Verify a GitHub Actions APK build passes on the resulting commit.
- [ ] Verify a tagged production release publishes both signed APKs.

## Review

The original download page exposed release URLs before any release assets existed, so the links correctly rendered as empty/404 destinations. The Android certification workflow is now the canonical CI publisher: it builds both release APKs, runs the existing Android tests, verifies APK alignment, checks package IDs, verifies the shared CI signing certificate, uploads the pair as a workflow artifact, and publishes `FadCam.apk` and `TV49East.apk` to the mutable `latest` GitHub Release. The verified run completed successfully on commit `bb81c0c6ae339bb0eb47271b4336c99c3d0e20da`, and the release contains both APK assets.

Production signing remains separate and still requires the production release workflow and its signing secrets.
