#!/usr/bin/env bash
set -euo pipefail

APK="${1:-}"
if [[ -z "$APK" || ! -s "$APK" ]]; then
  echo "Usage: $0 /path/to/FadCam.apk" >&2
  exit 2
fi

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -d "$SDK_ROOT/build-tools" ]] || { echo "Android SDK build-tools not found" >&2; exit 2; }

mapfile -t TOOLS < <(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d -print 2>/dev/null | sort -V -r)
AAPT=""; APKSIGNER=""; ZIPALIGN=""
for d in "${TOOLS[@]}"; do
  [[ -x "$d/aapt" && -z "$AAPT" ]] && AAPT="$d/aapt"
  [[ -x "$d/apksigner" && -z "$APKSIGNER" ]] && APKSIGNER="$d/apksigner"
  [[ -x "$d/zipalign" && -z "$ZIPALIGN" ]] && ZIPALIGN="$d/zipalign"
done
[[ -x "$AAPT" && -x "$APKSIGNER" && -x "$ZIPALIGN" ]] || { echo "Required Android build tools missing" >&2; exit 2; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
"$AAPT" dump badging "$APK" > "$TMP/badging"
"$AAPT" dump permissions "$APK" > "$TMP/permissions"
"$AAPT" dump xmltree "$APK" AndroidManifest.xml > "$TMP/manifest"
"$APKSIGNER" verify --verbose --print-certs "$APK" > "$TMP/signature"

fail=0; warn=0
pass(){ echo "PASS: $*"; }
warn(){ echo "WARN: $*"; warn=$((warn+1)); }
bad(){ echo "FAIL: $*"; fail=$((fail+1)); }

if grep -Fq "package: name='com.fadcam'" "$TMP/badging"; then pass "package id is com.fadcam"; else bad "unexpected package id"; fi
if grep -Eq "targetSdkVersion:'36'" "$TMP/badging"; then pass "target SDK is 36"; else warn "target SDK is not reported as 36"; fi

"$ZIPALIGN" -c -P 16 -v 4 "$APK" >/dev/null
pass "APK zip alignment verified"

grep -Fq "Verified using v2 scheme" "$TMP/signature" && pass "APK Signature Scheme v2 verified" || bad "APK Signature Scheme v2 is not verified"
grep -Fq "Verified using v3 scheme" "$TMP/signature" && pass "APK Signature Scheme v3 verified" || warn "APK Signature Scheme v3 is not verified"
CERT="$(sed -n 's/.*certificate SHA-256 digest: //p' "$TMP/signature" | head -n1)"
[[ -n "$CERT" ]] && pass "signing certificate SHA-256 present: $CERT" || bad "signing certificate digest missing"

# These capabilities are the sensitive internet-sideload triggers and other
# identity/communications capabilities that must not accidentally ship.
for p in \
  android.permission.READ_SMS \
  android.permission.RECEIVE_SMS \
  android.permission.SEND_SMS \
  android.permission.READ_CALL_LOG \
  android.permission.WRITE_CALL_LOG \
  android.permission.PROCESS_OUTGOING_CALLS \
  android.permission.BIND_NOTIFICATION_LISTENER_SERVICE \
  android.permission.BIND_ACCESSIBILITY_SERVICE; do
  if grep -Fq "$p" "$TMP/permissions" || grep -Fq "$p" "$TMP/manifest"; then bad "sensitive capability present: $p"; else pass "sensitive capability absent: $p"; fi
done

# Production release should not expose broad media/all-files/battery-bypass access.
for p in \
  android.permission.READ_MEDIA_VIDEO \
  android.permission.WRITE_MEDIA_VIDEO \
  android.permission.MANAGE_EXTERNAL_STORAGE \
  android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; do
  if grep -Fq "$p" "$TMP/permissions" || grep -Fq "$p" "$TMP/manifest"; then bad "production-hardening permission still present: $p"; else pass "production-hardening permission absent: $p"; fi
done

if grep -Fq "FadRecScreenshotAccessibilityService" "$TMP/manifest"; then bad "legacy AccessibilityService is still packaged"; else pass "legacy AccessibilityService is absent from final manifest"; fi
if grep -Fq "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" "$TMP/manifest"; then pass "specialUse FGS subtype property is present"; else bad "specialUse FGS subtype property is missing"; fi

echo
printf '%s\n' "=== Sensitive capabilities retained for documented core features ==="
for p in \
  android.permission.CAMERA \
  android.permission.RECORD_AUDIO \
  android.permission.ACCESS_FINE_LOCATION \
  android.permission.ACCESS_COARSE_LOCATION \
  android.permission.SYSTEM_ALERT_WINDOW \
  android.permission.FOREGROUND_SERVICE_CAMERA \
  android.permission.FOREGROUND_SERVICE_MICROPHONE \
  android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION \
  android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK \
  android.permission.FOREGROUND_SERVICE_DATA_SYNC \
  android.permission.FOREGROUND_SERVICE_SPECIAL_USE; do
  if grep -Fq "$p" "$TMP/permissions" || grep -Fq "$p" "$TMP/manifest"; then warn "review/document core-feature use: $p"; fi
done

echo
printf '%s\n' "=== Packaged services (from final APK manifest) ==="
awk '/E: service /{n=18} n>0{print; n--}' "$TMP/manifest" | sed -n '1,320p'

echo
printf '%s\n' "=== APK forensic summary ==="
echo "APK: $APK"
echo "Size: $(stat -c '%s' "$APK") bytes"
echo "SHA-256: $(sha256sum "$APK" | awk '{print $1}')"
echo "Signing certificate SHA-256: ${CERT:-unknown}"
echo "Failures: $fail"
echo "Warnings: $warn"
if (( fail > 0 )); then echo "FORENSIC RESULT: NOT READY"; exit 1; fi
echo "FORENSIC RESULT: APK-LEVEL READINESS PASSED WITH $warn REVIEW WARNING(S)"
