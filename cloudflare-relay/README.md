# TV 49 East Cloudflare Relay

Cloudflare Worker + Durable Object relay for worldwide FadCam HLS delivery without a VPS.

## Endpoints

- `GET /health` — deployment health check.
- `GET /tunnel?stream=<id>&ticket=<device-ticket>` with `Upgrade: websocket` — authenticated producer tunnel.
- `GET /v1/relay?id=<id>&ticket=<viewer-ticket>` — public HLS entry point; optional `path` selects an allowlisted upstream resource.

## Security model

Viewer tickets and device tickets are short-lived HMAC-SHA256 tokens. The Worker validates the ticket before routing to the Durable Object. The relay never accepts an arbitrary upstream URL, preventing an open-proxy/SSRF design.

`RELAY_SIGNING_SECRET` must match the server-side signer used by the Vercel control plane. `RELAY_DEVICE_SECRET` is separate and is only for trusted producer/tunnel clients. Neither secret belongs in the TV APK.

Transport is HTTPS/WSS. This is not media-layer cryptographic E2EE; strict E2EE would additionally encrypt HLS media on the producer and deliver decryption keys through a separate authenticated channel.

## Producer protocol

After the WebSocket is accepted, the relay sends:

```json
{"type":"ready","stream":"creator-001","protocol":1}
```

For each viewer request it sends a text frame:

```json
{"type":"request","id":42,"method":"GET","path":"/live.m3u8","headers":{"accept":"...","user-agent":"..."}}
```

The producer must answer with:

```json
{"type":"response","id":42,"status":200,"headers":{"content-type":"application/vnd.apple.mpegurl"}}
```

Body data is sent as binary WebSocket frames. The first four bytes are an unsigned big-endian request ID; the remaining bytes are the response payload. The producer finishes with:

```json
{"type":"end","id":42}
```

For an error:

```json
{"type":"error","id":42,"error":"upstream_unavailable"}
```

The relay buffers each individual HLS response up to 16 MiB. HLS segments are therefore forwarded without base64 encoding, while playlists remain small and can be rewritten by the producer/server-tap layer.

## Deployment

From this directory:

```bash
npm install
npm run typecheck
npx wrangler deploy
```

Set production secrets with Wrangler:

```bash
openssl rand -hex 32 | npx wrangler secret put RELAY_SIGNING_SECRET
openssl rand -hex 32 | npx wrangler secret put RELAY_DEVICE_SECRET
```

The producer must use a short-lived device ticket signed with `RELAY_DEVICE_SECRET`; the Vercel control plane must sign viewer tickets with `RELAY_SIGNING_SECRET`.
