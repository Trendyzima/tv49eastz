# TV 49 East worldwide Edge control plane

This directory is the public **control plane** for worldwide FadCam playback. It does not transcode or continuously proxy HLS through Vercel.

## Production path

```text
FadCam producer
  -> local HLS (private phone only)
  -> authenticated device-ticket provisioning
  -> Cloudflare WSS producer tunnel
  -> one RelayTunnel Durable Object per stream
  -> Cloudflare edge cache / HLS relay
  -> TV 49 East Edge viewer ticket
  -> TV 49 East binary APK
```

The phone's private address, client certificate, gateway API key and publishing secret are never shipped in the receiver APK.

## Required Vercel Production variables

```text
PUBLIC_RELAY_URL=https://stream.example.com/v1/relay
PUBLIC_STREAM_ID=creator-001
PUBLIC_STREAM_NAME=FadCam Live
PUBLIC_STREAM_OWNER=FadCam Creator
PUBLIC_GATEWAY_URL=https://gateway.example.com
EDGE_SIGNING_SECRET=<long-random-secret>
EDGE_PUBLISH_SECRET=<different-long-random-secret>
EDGE_DEVICE_SIGNING_SECRET=<same value as Cloudflare RELAY_DEVICE_SECRET>
```

`PUBLIC_RELAY_URL` must be a real public HTTPS Cloudflare relay endpoint. Do not include `?id=...` or a viewer ticket in the variable; those are appended dynamically.

`PUBLIC_STREAM_ID` is only the default/catalog channel. It is no longer a global relay restriction. Trusted publish calls may issue tickets for any valid stream id owned by the authenticated control-plane session.

## Receiver API

`GET /api/catalog` returns the default FadCam channel and a 15-minute signed playback URL. The Android receiver loads this catalog at startup.

`GET /api/live?ticket=...` verifies the HMAC-SHA256 ticket and redirects to the HTTPS relay. Relay tickets are bound to their stream and expire automatically.

## Trusted publishing API

`POST /api/publish` requires `Authorization: Bearer <EDGE_PUBLISH_SECRET>` and an existing authenticated gateway session. It probes the gateway session before issuing a 15-minute viewer ticket. The request may specify any valid stream id; ownership/authentication remains the responsibility of the control plane.

`POST /api/device-ticket` is a trusted provisioning endpoint protected by the same server-side publish secret. It mints a short-lived producer ticket for the requested stream. The endpoint is intended for an authenticated provisioning service to call; **never put `EDGE_PUBLISH_SECRET` or `EDGE_DEVICE_SIGNING_SECRET` in the producer APK**.

## Security model

There are three separate trust boundaries:

1. **Producer/device:** a short-lived stream-bound device ticket authenticates the outbound WSS tunnel.
2. **Edge control plane:** Vercel uses HMAC-SHA256, short TTLs and stream binding for viewer/publisher handoffs.
3. **Viewer/media:** TV 49 East accepts only HTTPS playback URLs. Cloudflare handles edge fan-out and relay transport.

This is encrypted transport in transit (TLS/WSS). It is not claimed to be cryptographic end-to-end media encryption where the gateway/relay cannot see media. If that stronger property is required, HLS content encryption and per-viewer key delivery must be added at the media producer/relay and receiver layers.

## Deployment

Set the Vercel project's Root Directory to `vercel-edge`. Add the Production variables above and ensure `EDGE_SIGNING_SECRET` matches Cloudflare `RELAY_SIGNING_SECRET`, while `EDGE_DEVICE_SIGNING_SECRET` matches Cloudflare `RELAY_DEVICE_SECRET`.

After deployment, `GET /api/health` should return `ok: true`. Do not deploy placeholder relay URLs or secrets.
