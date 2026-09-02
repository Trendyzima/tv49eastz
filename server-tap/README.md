# Server Tap

A standalone, read-only adapter for the existing FadCam local HTTP surface.

## Zero-configuration FadCam discovery

The production path no longer requires a manually supplied `FADCAM_LOCAL_IP` or `TAP_UPSTREAM`.

When `TAP_UPSTREAM` is omitted, server-tap automatically:

1. checks `127.0.0.1:8080` first (the normal same-device FadCam deployment);
2. checks active local IPv4 interfaces;
3. scans the directly connected IPv4 LANs for the FadCam HTTP port;
4. accepts a host only when `GET /live.m3u8` returns an HLS playlist with a fragmented-MP4/media reference;
5. retries discovery until FadCam becomes available.

This means a DHCP change does not require an IP to be copied into a production config before the next server-tap start. The discovery is local-only; the public internet never needs to know the FadCam device's private address.

The default FadCam HTTP port is `8080`, matching the documented FadCam Remote live-stream endpoint.

## Non-negotiable boundary

The tap never modifies the upstream server implementation. It only issues `GET` requests to the discovered or explicitly configured upstream origin. `POST`, `PUT`, `PATCH`, and `DELETE` are rejected before they can reach the upstream server.

## What it exposes

- `GET /health` — tap health and counters.
- `GET /status` — read the upstream status endpoint.
- `GET /live.m3u8` — fetch and rewrite the upstream HLS playlist.
- `GET /init.mp4` — read the upstream HLS initialization segment.
- `GET /audio/volume` — read the upstream volume endpoint.
- `GET /hls/<opaque-url>` — proxy an HLS playlist/media resource after validating that it resolves to the configured/discovered upstream origin and an allowed media path.

The tap does **not** expose or forward the upstream remote-control POST endpoints.

## Configuration

The only required listener configuration is:

```text
TAP_LISTEN=127.0.0.1:8788
TAP_TIMEOUT=10s
TAP_MAX_PLAYLIST_BYTES=1048576
TAP_MAX_PROXY_BODY_BYTES=67108864
```

Optional discovery tuning:

```text
TAP_DISCOVERY_PORT=8080
TAP_DISCOVERY_TIMEOUT=800ms
TAP_DISCOVERY_WORKERS=32
TAP_DISCOVERY_MAX_HOSTS=512
```

`TAP_UPSTREAM` remains available as an explicit diagnostic/override path, but production should leave it unset so discovery is automatic. Client requests can never supply an arbitrary upstream URL. Redirects are disabled so an accepted origin cannot bounce the tap to another host.

## HLS behavior

The upstream server's HLS playlist can contain `/init.mp4` and media-segment references. The tap rewrites those references to opaque local `/hls/...` URLs. Each opaque URL decodes to a target that is checked against the discovered/configured upstream scheme/host and a narrow media-path allowlist before it is fetched.

Media segments are copied without transcoding. Playlists are rewritten only to replace upstream resource references.

The upstream streaming flow documents `GET /live.m3u8` and its `#EXT-X-MAP` initialization reference as the live HLS path.

## Run

```bash
go run .
```

Or build a binary:

```bash
go build -trimpath -o server-tap .
```

## Tests

```bash
go test ./...
```

The tests verify the read-only mutation boundary, HLS URI rewriting, upstream-origin enforcement, and automatic FadCam discovery/playlist validation.
