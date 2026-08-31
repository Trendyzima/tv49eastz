# CI / Build Lessons

## 2026-08-31 — Android zipalign certification

- `zipalign` does not expose a `version` subcommand. Invoking `zipalign version` is parsed as an invalid command and exits with status 2 after printing the usage banner.
- A valid health check must use the executable's help/usage path (for example `zipalign -h`), inspect the banner, and explicitly accept its expected usage exit status.
- Android SDK build-tools discovery and tool validation are separate concerns: first resolve an executable `zipalign` under `$ANDROID_HOME/build-tools`, then validate it, then use `zipalign -c -P 16 -v 4` against real APK outputs.
- Do not treat a successful build followed by a certification-tool invocation failure as an APK build failure; diagnose the first failing command in the certification step precisely.
