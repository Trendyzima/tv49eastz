# TV 49 East worldwide Edge control plane

This directory is the public **control plane** for worldwide FadCam playback. It does not transcode or continuously proxy HLS through Vercel.

## Production path

```text
FadCam producer
  -> local HLS (private phone only)
  -> authenticated outbound device tunnel (mTLS)
  -> stream-gateway / relay
  -> public HTTPS HLS relay
  -> TV 49 East Edge ticket
  -> TV 49 East binary APK
```

The phone's private address, client certificate, gateway API key and publishing secret are never shipped in the receiver APK.

## Required Vercel Production variables

```text
PUBLIC_RELAY_URL=https://stream.example.com/v1/relay?id=creator-001
PUBLIC_STREAM_ID=creator-001
PUBLIC_STREAM_NAME=FadCam Live
PUBLIC_STREAM_OWNER=FadCam Creator
PUBLIC_GATEWAY_URL=https://gateway.example.com
EDGE_SIGNING_SECRET=<long-random-secret>
EDGE_PUBLISH_SECRET=<different-long-random-secret>
```

`PUBLIC_RELAY_URL` must be a real public HTTPS HLS relay. It must not be a phone LAN URL and must not contain credentials or a fragment.

## Receiver API

`GET /api/catalog` returns the FadCam channel and a 15-minute signed playback URL. The Android receiver loads this catalog at startup.

`GET /api/live?ticket=...` verifies the HMAC-SHA256 ticket and redirects to the HTTPS relay. Tickets expire automatically and are bound to `PUBLIC_STREAM_ID`.

## Trusted publishing API

`POST /api/publish` requires `Authorization: Bearer <EDGE_PUBLISH_SECRET>` and an existing authenticated gateway session. It probes the gateway session before issuing a 15-minute viewer ticket.

## Security model

There are three separate trust boundaries:

1. **Producer/device:** FadCam authenticates to the private device-tunnel/gateway path using device identity and mTLS.
2. **Edge control plane:** Vercel uses HMAC-SHA256, short TTLs and stream binding for viewer/publisher handoffs.
3. **Viewer/media:** TV 49 East accepts only HTTPS playback URLs. The relay/gateway provides the actual HLS media and capability checks.

This is encrypted transport in transit (TLS/mTLS). It is not claimed to be cryptographic end-to-end media encryption where the gateway/relay cannot see media. If that stronger property is required, HLS content encryption and per-viewer key delivery must be added at the media producer/relay and receiver layers.

## Deployment

Set the Vercel project's Root Directory to `vercel-edge`. After adding the Production variables, `GET /api/health` should return `ok: true`.

Do not deploy placeholder relay URLs or secrets. A successful Vercel build with missing environment variables is intentionally reported as `edge_not_configured`.
