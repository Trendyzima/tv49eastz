# TV 49 East end-to-end streaming deployment

This directory is the wiring contract for the real FadCam -> TV 49 East path. It deliberately keeps the FadCam server unchanged and keeps private LAN addressing off the client.

## Canonical path

```text
FadCam :8080
   |
   | local GET
   v
server-tap :8788
   |
   | outbound TLS 1.3 + mTLS
   v
device-tunnel gateway :9443
   |
   | authenticated /device/<device_id>/...
   v
TUNNEL_PROXY_LISTEN :8785
   |
   v
stream-catalog :8790
   |
   | /v1/catalog + /v1/relay
   v
public HTTPS edge
   |
   v
TV 49 East Android receiver
   |
   v
Media3 / ExoPlayer -> PlayerView -> screen
```

`origin-shield` is an independent cache tier and is not inserted between the existing capability-based `stream-catalog /v1/relay` contract and the receiver unless it is explicitly deployed as the public edge. Do not run two public playback contracts for the same channel.

## Server-room wiring

Run `server-tap` locally beside FadCam. It may discover the FadCam HTTP server automatically, or an operator may provide `TAP_UPSTREAM` for diagnostics.

```text
TAP_LISTEN=127.0.0.1:8788
TAP_UPSTREAM=http://<fadcam-lan-ip>:8080
TUNNEL_LOCAL_ADDR=127.0.0.1:8788
TUNNEL_GATEWAY=<public-edge>:9443
TUNNEL_DEVICE_ID=<enrolled-device-id>
```

The tunnel agent is outbound-only. The consumer never learns the FadCam LAN address.

## Public-edge wiring

The tunnel gateway must expose the device route only after mTLS identity and enrollment checks. The catalog and relay then use the gateway's loopback proxy:

```text
TUNNEL_PROXY_LISTEN=127.0.0.1:8785
CATALOG_LISTEN=127.0.0.1:8790
TUNNEL_PROXY_BASE_URL=http://127.0.0.1:8785
CREATOR_REGISTRY_FILE=/secure/tv49eastz/creators.json
CREATOR_PUBLISH_KEY=<secret>
RELAY_SIGNING_SECRET=<different-secret>
```

The public HTTPS reverse proxy exposes the catalog's consumer API. It must not expose ports 8785, 8788, or 8080.

## Channel enrollment

A channel is publishable only when its device is enrolled in the tunnel gateway and the channel is allowed for that device. Register the logical channel in the catalog:

```http
POST /v1/creators/channels
Authorization: Bearer <CREATOR_PUBLISH_KEY>
Content-Type: application/json

{
  "id": "creator-001",
  "name": "Creator Live",
  "owner": "Creator",
  "country": "KE",
  "language": "en",
  "source": "fadcam",
  "device_id": "fadcam-production-001",
  "stream_path": "/live.m3u8"
}
```

The receiver must consume the public `/v1/catalog` response. For a FadCam channel, its playback URL is the public catalog relay route, never `192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`, or `127.0.0.1`.

## Runtime acceptance sequence

1. `GET /health` on server-tap is healthy.
2. `GET /live.m3u8` on server-tap returns an HLS manifest.
3. The tunnel gateway accepts the enrolled device and serves `/device/<id>/live.m3u8`.
4. `GET /health` on stream-catalog is healthy.
5. `GET /v1/origin/channels?id=<channel>` with `X-Origin-Key` returns only the opaque device ID and `/live.m3u8` path.
6. `GET /v1/catalog` returns the authorized FadCam channel.
7. `GET /v1/relay?id=<channel>` returns a rewritten HLS manifest with no private-origin URL.
8. Every rewritten media URL returns the corresponding init/media bytes through `/v1/relay-asset`.
9. The Android receiver prepares the returned HLS URL with Media3 and displays decoded video.
10. Stop-TV/revocation invalidates the session/capability immediately.

A source-code or CI pass proves the contracts only. The final step must be performed on a physical receiver over a different network from the FadCam LAN before claiming end-to-end certification.

## No-VPS option

The FadCam side can remain behind NAT because the device tunnel is outbound-only. A public edge service is still required for the rendezvous point and public HTTPS hostname. A Cloudflare Tunnel can publish an HTTP service without inbound router forwarding, but it does not replace the long-lived mTLS tunnel broker used by this repository. Do not advertise a pure edge-function deployment until that broker protocol has been redesigned for an edge-compatible transport.
