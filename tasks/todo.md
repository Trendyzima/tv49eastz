# APK download and build integration

- [x] Audit the Android modules and existing APK workflows.
- [x] Confirm the FadCam module and TV receiver module have APK-producing Gradle tasks.
- [x] Confirm the TV receiver application ID is `com.tv49.com`.
- [x] Add a dedicated APK download page/documentation with stable release links.
- [x] Add README navigation to the APK download page and direct latest-release APK links.
- [x] Keep CI APK artifacts and production release APK assets aligned with the documented filenames.
- [ ] Verify a GitHub Actions APK build passes on the resulting commit.
- [ ] Verify a tagged production release publishes both signed APKs.

## Review

The repository already had the core APK build machinery. The missing piece was discoverability: README did not expose a user-facing download page, while production release publishing was already prepared to attach `FadCam.apk` and `TV49East.apk`. This change makes the release surface explicit without bypassing the existing test/signing gates.
