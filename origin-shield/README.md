# TV 49 East Origin Shield

The origin shield is the protected middle tier between CDN edges and the FadCam/device tunnel. It is channel-aware and keeps the FadCam origin private.

```text
viewer -> CDN edge -> origin shield -> stream catalog -> device tunnel -> FadCam
```

## Request contract

- `GET|HEAD /channel/{channel_id}/live.m3u8` — fetches the live playlist from the tunnel and rewrites media/URI attributes to shield paths. Playlists are never cached.
- `GET|HEAD /channel/{channel_id}/{asset}` — serves `.m4s`, `.mp4`, `.ts`, or nested `.m3u8` assets from an in-memory bounded cache.
- `GET /health` — health and cache state.
- `GET /metrics` — Prometheus-compatible counters.

Example playlist URL:

`https://edge.example/channel/news/live.m3u8`

The viewer never receives a FadCam IP. The shield receives only an opaque `device_id` from the authenticated catalog endpoint and talks to the tunnel's `/device/{device_id}/...` route.

## Protection model

1. The catalog is queried over HTTPS using `X-Origin-Key`.
2. Only `source=fadcam`, a non-empty device ID, and `/live.m3u8` are accepted.
3. Asset paths reject traversal, control characters, absolute URLs, and unsupported extensions.
4. Segment/object misses use per-key singleflight: 1,000 simultaneous requests for the same segment produce one upstream fetch.
5. Origin fetches have independent bounded contexts, so one viewer disconnect cannot cancel a shared fetch.
6. A bounded semaphore limits concurrent tunnel/origin fetches.
7. If an origin fetch fails, the cached device target is invalidated and the catalog is resolved again once. This supports FadCam/device reconnects without exposing the old address.
8. Cache is memory-only; there is no disk spool, recording database, or persistent media store.

## Environment

- `SHIELD_LISTEN` — default `:8795`
- `SHIELD_CATALOG_URL` — required HTTPS catalog base URL
- `SHIELD_CATALOG_KEY` — required secret matching catalog `ORIGIN_RESOLVE_KEY`
- `SHIELD_TUNNEL_URL` — required tunnel proxy base URL
- `SHIELD_CACHE_BYTES` — default `536870912` (512 MiB)
- `SHIELD_MAX_OBJECT_BYTES` — default `33554432` (32 MiB)
- `SHIELD_SEGMENT_TTL` — default `30s`
- `SHIELD_ORIGIN_TIMEOUT` — default `10s`
- `SHIELD_MAX_ORIGIN_CONCURRENCY` — default `256`
- `SHIELD_TARGET_TTL` — default `30s`

## Catalog requirement

The catalog must set `ORIGIN_RESOLVE_KEY` to the same secret supplied to the shield as `SHIELD_CATALOG_KEY`. The authenticated endpoint is:

`GET /v1/origin/channels?id={channel_id}`

It returns only the shield's origin contract (`channel_id`, `device_id`, `stream_path`, `source`). Public catalog responses do not need to expose the device ID.

## Scaling

Run multiple independent shields behind the CDN edge layer. Each shield protects its configured tunnel/origin pool. The shield is deliberately not a global CDN and does not attempt to solve global routing itself.
