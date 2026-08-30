# Server Tap

A standalone, read-only adapter for the existing camera/server HTTP surface.

## Non-negotiable boundary

The tap never modifies the upstream server implementation. It only issues `GET` requests to a statically configured upstream origin. `POST`, `PUT`, `PATCH`, and `DELETE` are rejected before they can reach the upstream server.

## What it exposes

- `GET /health` — tap health and counters.
- `GET /status` — read the upstream status endpoint.
- `GET /live.m3u8` — fetch and rewrite the upstream HLS playlist.
- `GET /init.mp4` — read the upstream HLS initialization segment.
- `GET /audio/volume` — read the upstream volume endpoint.
- `GET /hls/<opaque-url>` — proxy an HLS playlist/media resource after validating that it resolves to the configured upstream origin and an allowed media path.

The tap does **not** expose or forward the upstream remote-control POST endpoints.

## Configuration

```text
TAP_LISTEN=127.0.0.1:8788
TAP_UPSTREAM=http://127.0.0.1:8080
TAP_TIMEOUT=10s
TAP_MAX_PLAYLIST_BYTES=1048576
TAP_MAX_PROXY_BODY_BYTES=67108864
```

`TAP_UPSTREAM` is deliberately fixed at process configuration time. Client requests cannot supply an arbitrary upstream URL. Redirects are disabled so the configured origin cannot bounce the tap to another host.

## HLS behavior

The upstream server's HLS playlist can contain `/init.mp4` and media-segment references. The tap rewrites those references to opaque local `/hls/...` URLs. Each opaque URL decodes to a target that is checked against the configured upstream scheme/host and a narrow media-path allowlist before it is fetched.

Media segments are copied without transcoding. Playlists are rewritten only to replace upstream resource references.

The upstream streaming flow documents `GET /live.m3u8` and its `#EXT-X-MAP` initialization reference as the live HLS path. See the upstream streaming-testing documentation for the expected local server behavior.

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

The tests verify the read-only mutation boundary, HLS URI rewriting, upstream-origin enforcement, and prevention of external playlist targets.
