# TV 49 East CDN Edge

A lightweight, live-only HLS edge cache for horizontally scaling TV 49 East streams.

## Design

```text
FadCam -> server-tap -> device-tunnel -> origin -> CDN edges -> viewers
```

The edge does not record video and does not expose an arbitrary proxy. It accepts a single configured origin, fetches live HLS playlists without caching them, and caches immutable media segments (`.m4s`, `.mp4`, `.ts`) in a bounded in-memory LRU cache.

Concurrent requests for the same uncached segment are collapsed into one origin fetch. This prevents a viewer spike from multiplying origin load.

## Configuration

- `CDN_EDGE_LISTEN` — listen address, default `:8080`
- `CDN_EDGE_ORIGIN` — required fixed HTTP(S) origin base URL
- `CDN_EDGE_CACHE_BYTES` — memory cache budget, default 512 MiB
- `CDN_EDGE_MAX_OBJECT_BYTES` — maximum cached object, default 32 MiB
- `CDN_EDGE_SEGMENT_TTL` — segment cache TTL, default 30s
- `CDN_EDGE_ORIGIN_TIMEOUT` — origin request timeout, default 10s

## Endpoints

- `GET /health` — health check
- `GET /metrics` — lightweight Prometheus-style counters
- `GET /live.m3u8` — live playlist; never cached
- `GET /*.m4s`, `/*.mp4`, `/*.ts` — cacheable media objects

## Scaling model

The binary is stateless apart from its local bounded cache. Add edge nodes horizontally and route viewers to healthy regional edges. No viewer account, recording database, or persistent video store is required.

This is an edge-cache component, not a claim that one machine can serve millions of viewers. Million-viewer capacity comes from aggregate edge bandwidth and many independently scalable nodes.
