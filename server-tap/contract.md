# Read-only server contract

The integration layer treats the existing server as an external dependency.

| Operation | Upstream path | Tap behavior |
|---|---|---|
| GET | `/live.m3u8` | Fetch, validate, rewrite HLS references |
| GET | `/init.mp4` | Stream through without changing bytes |
| GET | media fragments referenced by HLS | Stream through without changing bytes |
| GET | `/status` | Fetch and normalize status |
| GET | `/audio/volume` | Optional read-only telemetry |
| POST | any control endpoint | Do not proxy |
| PUT/PATCH/DELETE | any endpoint | Do not proxy |

## HLS rules

- Preserve the upstream media payload bytes.
- Rewrite only URLs in the playlist that point at the upstream host.
- Preserve HLS timing, sequence numbers, codecs, and segment names.
- Do not transcode at the tap.
- Use short-lived cache entries for manifests; do not cache live media indefinitely.

## Network rule

The public client must never be given a private LAN URL. The gateway owns the public URL and the tap owns the private upstream connection.
