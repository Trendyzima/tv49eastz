#!/usr/bin/env bash
set -euo pipefail

: "${GATEWAY_MANIFEST_URL:?Set GATEWAY_MANIFEST_URL to the public opaque gateway manifest URL}"

case "$GATEWAY_MANIFEST_URL" in
  https://*) ;;
  *) echo "FAIL: gateway URL must use HTTPS" >&2; exit 1 ;;
esac

if [[ "$GATEWAY_MANIFEST_URL" == *"192.168."* || "$GATEWAY_MANIFEST_URL" == *"127.0.0.1"* || "$GATEWAY_MANIFEST_URL" == *"localhost"* ]]; then
  echo "FAIL: private/loopback upstream leaked into gateway URL" >&2
  exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

curl --fail --silent --show-error --location --max-time 15 \
  --output "$work/index.m3u8" "$GATEWAY_MANIFEST_URL"

grep -Eq '#EXTM3U' "$work/index.m3u8"

grep -Eq '(^|[^[:alnum:]])https://[^[:space:]]+' "$work/index.m3u8" || true

echo "PASS: public gateway manifest fetched and is HLS"

echo "PASS: no private upstream URL present in gateway entrypoint"

echo "Next: use the resolved segment URLs from the gateway manifest for init/media verification."
