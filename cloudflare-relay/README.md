# TV 49 East Cloudflare Relay

Cloudflare Worker + Durable Object relay for worldwide FadCam HLS delivery without a VPS.

## Million-scale architecture

The relay is intentionally **multi-tenant**. There is no global `PUBLIC_STREAM_ID` gate in the Worker. Every authenticated stream id is deterministically mapped to its own Durable Object with `idFromName(stream)`.

```text
FadCam producer
   │ WSS + short-lived device ticket
   ▼
Cloudflare Worker
   │ verify device ticket
   ▼
RelayTunnel(stream-id) ───── one logical DO per live stream
   │
   ├── one producer tunnel
   ├── request coalescing (same HLS path)
   ├── streaming ReadableStreams
   ├── per-viewer backpressure isolation
   └── bounded in-flight work
   ▲
   │
Cloudflare edge cache
   │ signed viewer ticket checked before cache lookup
   ▼
Millions of viewers / TVs
```

This changes the scaling unit from **one relay for one stream** to **one small stateful object per stream**. Cloudflare documents unlimited Durable Objects within an account/class, while an individual object is single-threaded and has a soft throughput guideline around 1,000 requests/sec. Horizontal scale therefore comes from creating many independent stream objects, not from putting all streamers into one object.

The viewer path also uses Cloudflare's Cache API for short-lived HLS objects. The viewer ticket is never part of the cache key, so thousands of viewers requesting the same segment do not each create a new producer request. Cache API entries are data-center local, so production deployments should use a Cloudflare custom domain and CDN/Workers caching configuration for broader edge distribution.

## Endpoints

- `GET /health` — deployment health check.
- `GET /tunnel?stream=<id>&ticket=<device-ticket>` with `Upgrade: websocket` — authenticated producer tunnel.
- `GET /v1/relay?id=<id>&ticket=<viewer-ticket>&path=<hls-path>` — public HLS entry point.

## Producer protocol v2

After the WebSocket is accepted, the relay sends:

```json
{"type":"ready","stream":"creator-001","protocol":2,"capabilities":["request-coalescing","streaming","cancel"]}
```

For each **unique in-flight HLS path** it sends one request to the producer, even when many viewers are waiting for that same path:

```json
{"type":"request","id":42,"method":"GET","path":"/live.m3u8","headers":{"accept":"...","user-agent":"tv49eastz-cloudflare-relay/2"}}
```

The producer answers with headers:

```json
{"type":"response","id":42,"status":200,"headers":{"content-type":"application/vnd.apple.mpegurl"}}
```

Body data is sent as binary WebSocket frames. The first four bytes are an unsigned big-endian request ID; the remaining bytes are the response payload. Finish with:

```json
{"type":"end","id":42}
```

Or report an error:

```json
{"type":"error","id":42,"error":"upstream_unavailable"}
```

If all viewers leave before completion, the relay sends best-effort cancellation:

```json
{"type":"cancel","id":42}
```

### Why streaming matters

The previous implementation accumulated every response into an array and copied it into a second buffer before returning it. The scaled implementation streams chunks directly to viewer `ReadableStream`s and caps the total response at 64 MiB. This avoids turning viewer fan-out into per-request memory amplification.

Slow viewers are isolated with a byte-based `ReadableStream` high-water mark. If a viewer cannot keep up, that viewer is dropped rather than allowing one stalled connection to consume the stream object's memory.

## Edge fan-out and caching

Caching is deliberately applied only to safe HLS resources:

| Path | Edge TTL | Reason |
|---|---:|---|
| `/live.m3u8` | 1s | reduces playlist polling while staying live |
| `/hls/<segment>` | 30s | segment URLs are normally immutable |
| `/init.mp4` | 1h | initialization media is normally immutable |
| `/status`, `/audio/volume` | no cache | state/control resources |

The cache key is derived from `stream + path`, never the viewer ticket. Authorization still happens before a cache hit is returned.

Cloudflare's Cache API is data-center local rather than globally replicated, so this is a first-level fan-out shield, not a promise that every POP shares one cache entry. For the largest deployments, use a custom domain plus Cloudflare's normal Workers/CDN caching path and tiered-cache features where appropriate.

## Security model

Viewer tickets and device tickets are short-lived HMAC-SHA256 tokens. The Worker verifies them before routing. The relay never accepts an arbitrary upstream URL, preventing an open-proxy/SSRF design.

- `RELAY_SIGNING_SECRET` signs viewer relay tickets.
- `RELAY_DEVICE_SECRET` verifies producer tickets.
- Vercel `EDGE_SIGNING_SECRET` must match `RELAY_SIGNING_SECRET`.
- Vercel `EDGE_DEVICE_SIGNING_SECRET` must match `RELAY_DEVICE_SECRET`.
- `EDGE_PUBLISH_SECRET` protects trusted control-plane provisioning calls.
- None of these secrets belongs in the TV APK.

`/api/device-ticket` is a **trusted server-to-server provisioning endpoint**. It mints a short-lived producer ticket; an authenticated provisioning service must hand that ticket to the producer. Do not expose `EDGE_PUBLISH_SECRET` to mobile clients.

Transport is HTTPS/WSS. This is not media-layer cryptographic E2EE; strict E2EE would additionally encrypt HLS media on the producer and deliver decryption keys through a separate authenticated channel.

## Multi-tenant control plane contract

`vercel-edge/api/publish.ts` now accepts any valid stream id rather than requiring one global `PUBLIC_STREAM_ID`. The control plane is responsible for authenticating the owner/session and passing the correct stream id. The signed viewer ticket binds access to that stream.

Example trusted publish request:

```http
POST /api/publish
Authorization: Bearer <EDGE_PUBLISH_SECRET>
Content-Type: application/json

{"session":"gateway-session-123","stream":"creator-8f2c"}
```

Producer provisioning uses:

```http
POST /api/device-ticket
Authorization: Bearer <EDGE_PUBLISH_SECRET>
Content-Type: application/json

{"stream":"creator-8f2c"}
```

The returned device ticket is short-lived and stream-bound.

## Deployment

From this directory:

```bash
npm install
npm run typecheck
npx wrangler deploy
```

Set production secrets:

```bash
openssl rand -hex 32 | npx wrangler secret put RELAY_SIGNING_SECRET
openssl rand -hex 32 | npx wrangler secret put RELAY_DEVICE_SECRET
```

The Vercel control plane must use matching values for `EDGE_SIGNING_SECRET` and `EDGE_DEVICE_SIGNING_SECRET`.

For production, point `PUBLIC_RELAY_URL` at the Cloudflare HTTPS custom-domain endpoint, for example:

```text
https://relay.example.com/v1/relay
```

Do not put `?id=...` or a viewer ticket into `PUBLIC_RELAY_URL`; the control plane appends those values dynamically.

## Capacity model

This design is **million-streamer capable by architecture**, but no code change can honestly guarantee one million concurrent users without testing the actual Cloudflare account, plan, bitrate distribution, POP distribution, producer device fleet, and cache-hit ratio.

Cloudflare currently documents:

- unlimited Durable Objects within an account/class;
- a 32,768 WebSocket connection maximum per individual Durable Object, with practical CPU/memory limits potentially lower;
- approximately 500–1,000 requests/sec for a simple individual Durable Object, depending on workload;
- Workers Paid with no general daily request limit and 128 MB Worker memory;
- streaming response bodies without an enforced Worker response-size limit.

Therefore a million-streamer system should be modeled as many independent stream objects. A million **viewers** for a popular stream should be handled primarily by edge caching/fan-out; sending one producer request per viewer would defeat the architecture.

## Load test

A Node 24+ harness is included at `load-test.mjs`:

```bash
RELAY_URL=https://relay.example.com \
STREAM_ID=creator-8f2c \
TICKET='<viewer-ticket>' \
PATH_TO_TEST=/live.m3u8 \
REQUESTS=10000 \
CONCURRENCY=250 \
node load-test.mjs
```

It reports requests/sec, failures, bytes, p50, p95 and p99 latency. Run staged tests at 10k, 100k and higher request volumes before claiming production capacity.

## Remaining production requirements

1. The gateway/control plane must provide authenticated stream ownership and device provisioning; the relay cannot infer identity from a stream id.
2. Configure Cloudflare custom-domain routing and production cache policy; Cache API is local to the handling data center.
3. Put WAF/rate limiting in front of the public relay for abusive clients. The relay's bounded in-flight work is an application safety valve, not a replacement for account-level abuse controls.
4. Load-test with realistic HLS bitrates and segment durations. A million 4 Mbps viewers represents roughly 4 Tbps of viewer egress before protocol overhead, so bandwidth/fan-out is the dominant capacity variable.
5. Add production telemetry/Logpush and alerts for producer disconnects, cache hit ratio, 429 capacity responses, 503 producer-offline responses and p95/p99 latency.
6. For extremely popular streams, move toward a two-tier media architecture where cached HLS segments are served directly at the edge and the producer/DO only handles cache misses and live playlist refreshes.
