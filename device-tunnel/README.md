# Hardened FadCam → TV 49 East production tunnel

This component creates an **outbound-only TLS 1.3 + mTLS tunnel** from the FadCam server-room `server-tap` to the public tunnel broker. The FadCam HTTP server is not modified and its private LAN address is never published.

## Production data path

```text
FadCam production server :8080
        |
        | local/LAN GET only
        v
server-tap 127.0.0.1:8788
        |
        | outbound mTLS tunnel
        v
public tunnel broker :9443
        |
        | authenticated /device/<id>/...
        v
TUNNEL_PROXY_LISTEN 127.0.0.1:8785
        |
        v
TV 49 East FadCam catalog + relay :8790
        |
        | public HTTPS hostname
        v
TV 49 East Android consumers worldwide
```

## Why the local IP is not converted

A server-room address such as `192.168.x.x`, `10.x.x.x`, or `172.16-31.x.x` is private addressing. DNS cannot turn it into a public Internet address. The production design therefore uses an **outbound connection** from the server room to a public edge. Consumers connect to the public hostname; the edge sends requests back through the established tunnel.

This also avoids exposing the FadCam `:8080` listener or requiring inbound router port forwarding.

## Origin configuration

On the server-room host:

```text
TAP_LISTEN=127.0.0.1:8788
TAP_UPSTREAM=http://<fadcam-local-ip>:8080

TUNNEL_GATEWAY=<public-tunnel-host>:9443
TUNNEL_LOCAL_ADDR=127.0.0.1:8788
TUNNEL_DEVICE_ID=<enrolled-device-id>
TUNNEL_CA=/secure/tunnel/ca.pem
TUNNEL_CERT=/secure/tunnel/device.pem
TUNNEL_KEY=/secure/tunnel/device-key.pem
```

The tunnel agent only dials `TUNNEL_LOCAL_ADDR`; it never accepts an arbitrary destination from a consumer.

## Tunnel broker configuration

On the public edge host:

```text
TUNNEL_LISTEN=:9443
TUNNEL_PROXY_LISTEN=127.0.0.1:8785
DEVICE_REGISTRY_PATH=/secure/devices.json
TUNNEL_CA=/secure/tunnel/ca.pem
TUNNEL_CERT=/secure/tunnel/gateway.pem
TUNNEL_KEY=/secure/tunnel/gateway-key.pem
```

The broker verifies the device certificate, device identity, enrollment state, and channel authorization before forwarding a request.

## TV 49 East relay configuration

On the same public edge host (or another host that can reach `127.0.0.1:8785`):

```text
CATALOG_LISTEN=127.0.0.1:8790
TUNNEL_PROXY_BASE_URL=http://127.0.0.1:8785
CREATOR_REGISTRY_FILE=/secure/tv49east/creators.json
CREATOR_PUBLISH_KEY=<strong-random-secret>
RELAY_SIGNING_SECRET=<strong-random-secret>
```

The catalog now contains **FadCam channels only**. For a FadCam channel, the registry stores a logical `device_id` and fixed `/live.m3u8` path rather than a public origin URL. The relay obtains the playlist and HLS resources through the device tunnel and rewrites them to short-lived signed public relay URLs.

## Publisher registration

A trusted production publisher registers a channel at the catalog:

```http
POST /v1/creators/channels
Authorization: Bearer <CREATOR_PUBLISH_KEY>
Content-Type: application/json
```

```json
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

The `device_id` must already be enrolled in the tunnel broker and its registry must contain `creator-001: true` in that device's allowed channel map.

## Consumer path

The Android receiver fetches `/v1/catalog`, selects a FadCam channel, and requests:

```text
GET /v1/relay?id=creator-001
```

The relay fetches:

```text
/device/fadcam-production-001/live.m3u8
```

through the authenticated tunnel. The playlist's segment URLs are rewritten to `/v1/relay-asset` with an HMAC capability that expires after five minutes. No private FadCam IP is sent to the Android device.

## Gate 1 acceptance test

Run these checks in order:

```text
FadCam /live.m3u8
        ↓
server-tap /live.m3u8
        ↓
tunnel broker /device/<id>/live.m3u8
        ↓
TV 49 East /v1/relay?id=<channel>
        ↓
rewritten /v1/relay-asset?... 
        ↓
TV 49 East Android player
```

The final test must be performed from a different Internet connection from the server room. A successful LAN-only test does not prove worldwide reachability.

## Public edge options

The public catalog/relay needs a public HTTPS hostname. If the edge host has a public IP, DNS plus a reverse proxy can publish it. Caddy can automatically obtain and renew publicly trusted HTTPS certificates for a configured hostname. If the server room itself is behind CGNAT or has no inbound connectivity, an outbound application tunnel such as Cloudflare Tunnel can publish a local service without opening inbound ports.

The public edge is the Internet rendezvous point; the FadCam server remains local.
