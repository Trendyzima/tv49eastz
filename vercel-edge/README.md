# TV 49 East worldwide Vercel Edge

This directory is a **control-plane edge**, not a video transcoder. Vercel issues short-lived viewer tickets and hands the TV app/browser to the public HLS gateway. The producer's FadCam LAN address, device certificate and gateway API key never go into the APK or the Vercel client bundle.

## Deploy on Vercel

Set the Vercel project **Root Directory** to `vercel-edge`.

Required Environment Variables:

```text
PUBLIC_GATEWAY_URL=https://stream.example.com
PUBLIC_STREAM_ID=fadcam-device-stream
EDGE_SIGNING_SECRET=<long-random-secret>
EDGE_PUBLISH_SECRET=<different-long-random-secret>
```

Use different secrets for signing and publishing. Do not put gateway credentials in browser/mobile code.

After deployment:

```text
GET https://<your-vercel-domain>/api/health
```

must return `{"ok":true,"service":"tv49east-edge"}`.

## Publish a live session

A trusted publisher first obtains a live session from the authenticated production gateway/device path. Then call:

```http
POST /api/publish
Authorization: Bearer <EDGE_PUBLISH_SECRET>
Content-Type: application/json

{"session":"<gateway-session-id>","stream":"fadcam-device-stream"}
```

The response contains a short-lived `viewer_url`. Give that URL to TV 49 East or use it as the public HLS handoff.

## Critical deployment requirement

`PUBLIC_GATEWAY_URL` must be a **public HTTPS media endpoint** that can serve:

```text
GET /stream/<session>/index.m3u8
GET /stream/<session>/resource/<capability>
```

It must not expose the FadCam phone address. The current `stream-gateway` implementation is protected by device mTLS for its session-creation API, so do not simply put its mTLS-only listener behind Vercel. Keep device enrollment/session creation on the trusted producer side and expose only the already-authorized, capability-signed HLS media route through a properly secured public TLS edge.

## Why Vercel is not the HLS origin

The Vercel Edge Function is deliberately not used to proxy every HLS segment. The media gateway/tunnel remains responsible for the live stream; Vercel handles authentication and the public handoff. This avoids turning a serverless function into the high-bandwidth media relay.

## Worldwide behavior

Normal TV playback does **not** scan `192.168.x.x`, `10.x.x.x`, or other private LAN addresses. A producer can be on one Internet connection and a viewer can be on another network, mobile data, or another country. The public viewer URL resolves to HTTPS and the gateway reaches the producer through the outbound device tunnel.
