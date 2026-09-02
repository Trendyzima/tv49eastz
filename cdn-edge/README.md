# TV 49 East CDN Edge

Lightweight, live-only HLS edge for horizontally scaling TV 49 East to very large concurrent audiences without viewer accounts or permanent video storage.

## Architecture

```text
FadCam
  -> server-tap
  -> device-tunnel
  -> origin/shield
  -> regional CDN edges
  -> worldwide viewers
```

The edge is deliberately narrow: it serves HLS media, caches completed media segments, and never records a stream. Million-viewer capacity is an **aggregate deployment property**: multiple edge nodes provide the required aggregate network bandwidth. No single node is advertised as a million-viewer server.

## Hot-path rules

- `GET /live.m3u8` is always fetched from upstream and marked `no-store`.
- `.m4s`, `.mp4`, and `.ts` objects are cached in a bounded in-memory LRU.
- Concurrent misses for the same object are collapsed to one upstream fetch per edge.
- Upstream concurrency is bounded so a cache miss storm cannot overwhelm the origin.
- Playlist `URI="..."` attributes are rewritten safely, including `EXT-X-MAP`, `EXT-X-PART`, and preload-hint style tags.
- Foreign origins, traversal, malformed paths, and unsupported objects are rejected.
- Query strings are preserved for signed/tokenized upstream media URLs.
- There is no viewer login, viewer database, recording store, or arbitrary proxy.

## Configuration

- `CDN_EDGE_LISTEN` — listen address, default `:8080`
- `CDN_EDGE_ORIGIN` — required fixed HTTP(S) origin base URL
- `CDN_EDGE_CACHE_BYTES` — per-process RAM cache, default 512 MiB
- `CDN_EDGE_MAX_OBJECT_BYTES` — maximum upstream object, default 32 MiB
- `CDN_EDGE_SEGMENT_TTL` — cache lifetime, default 30s
- `CDN_EDGE_ORIGIN_TIMEOUT` — upstream timeout, default 10s
- `CDN_EDGE_MAX_ORIGIN_CONCURRENCY` — maximum simultaneous upstream fetches, default 256

## Endpoints

- `GET /health` — process health
- `GET /metrics` — Prometheus-compatible counters
- `GET /live.m3u8` — live playlist
- `GET|HEAD /*.m4s`, `/*.mp4`, `/*.ts` — media objects

## Scaling model

Run the same binary on independent regional nodes. Put a global DNS/load-balancing layer in front and remove unhealthy nodes automatically. Each node has its own cache, so there is no cache database or cluster membership requirement on the hot path.

For very large deployments, use a tiered topology:

```text
                    global routing
                         |
          +--------------+--------------+
          |              |              |
       Africa          Europe        Americas
          |              |              |
       edge x N       edge x N       edge x N
          \              |              /
           +--------- origin/shield ---+
```

A shield reduces the number of independent edge misses reaching FadCam. The shield/origin should have significantly more upstream capacity than any single edge and should be protected from public access.

At 4 Mbps average viewer bitrate, 1,000,000 simultaneous viewers represent about 4 Tbps of aggregate egress. Reaching that target requires enough independently provisioned edge bandwidth; software alone cannot manufacture network capacity.

## Production requirements outside the binary

- Raise the host file-descriptor/socket limits for the expected connection count.
- Use a kernel/network configuration appropriate for high connection concurrency.
- Terminate TLS at a hardened public reverse proxy/load balancer or at the edge host.
- Use health-aware global routing and regional capacity monitoring.
- Keep origin/shield addresses private; only edges should reach them.
- Measure real throughput and cache-hit ratio with staged load tests before increasing capacity.

## Validation

CI runs formatting, race-enabled tests, `go vet`, and a production build. The test suite includes a 500-concurrent-request miss-collapse test to verify that a burst of viewers does not multiply the upstream fetch for one segment.
