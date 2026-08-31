# Forensic Android Build Repair

- [x] Reproduce and identify the malformed Gradle task invocation from CI logs.
- [x] Verify Android SDK `zipalign` exists on the runner and distinguish discovery from execution.
- [x] Trace the certification failure to the unsupported `zipalign version` invocation.
- [x] Replace the unsupported health check with banner/status validation that accepts the tool's expected usage status.
- [x] Apply the fix to both Android certification workflows.
- [x] Preserve real APK certification with `zipalign -c -P 16 -v 4`.
- [x] Record the failure pattern in `tasks/lessons.md`.
- [ ] Verify the next clean GitHub Actions runs end-to-end; treat any subsequent failure as a new root-cause defect.

## Review

The repository was inspected against the failing CI evidence before changing the certification command. The current build logs show Gradle progressing through APK compilation/packaging; the reported `zipalign not installed` and later usage output were certification-layer defects, not evidence that the APK builds themselves failed. The final verification remains intentionally strict: real APKs must pass zipalign's check mode and the expected artifact count must be present.
