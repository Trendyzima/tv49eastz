# FadCam APK polish and integration

- [x] Audit current master/feature surfaces and identify disconnected features.
- [x] Verify capture-quality policy is implemented only in approved boundaries.
- [x] Harden capture-quality fallback and low-light policy.
- [x] Stop advertising unimplemented Live Studio roadmap capabilities as executable.
- [x] Deep-copy Studio runtime state to prevent caller mutation of shared registry state.
- [x] Add focused regression coverage.
- [ ] Wire new controls into the existing recording/settings path without competing surfaces.
- [ ] Audit remaining Live Studio runtime integration gaps.
- [ ] Run protected-boundary, Go, Android unit/test, APK build, packaging, and artifact checks.
- [ ] Fix every failing check and rerun until green.
- [ ] Final review: no stubs, no duplicate settings surfaces, no unsafe fallback.

## Review

The current pass deliberately preserves the protected FadCam application boundary. Android application source, resources, and manifest cannot be changed by policy; executable work must therefore remain in approved runtime/control-plane surfaces unless that boundary policy is explicitly changed.
