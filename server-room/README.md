# TV 49 East production server room

This is the production boundary between the existing FadCam local HTTP producer and worldwide TV 49 East consumers.

## End-to-end topology

```text
FadCam production server :8080
        |
        | local/LAN only
        v
server-tap 127.0.0.1:8788
        |
        | outbound TLS 1.3 + device mTLS
        v
FadCam device-tunnel agent
        |
        | outbound connection; no inbound port at origin
        v
public tunnel broker :9443
        |
        | authenticated device pool
        v
TUNNEL_PROXY_LISTEN 127.0.0.1:8785
        |
        v
TV 49 East stream-catalog/relay :8790
        |
        | HTTPS public hostname
        v
Worldwide TV 49 East consumers
```

The private FadCam address is never published to consumers. A private address such as `192.168.x.x`, `10.x.x.x`, or `172.16-31.x.x` cannot be converted into a globally routable address. The production solution is an outbound tunnel to a public edge, then HTTPS/DNS for consumers.

## Required production wiring

### Origin/server-room host

Run the existing FadCam server unchanged on its local address, for example:

```text
FadCam: http://192.168.1.50:8080
```

Configure server-tap:

```text
TAP_LISTEN=127.0.0.1:8788
TAP_UPSTREAM=http://192.168.1.50:8080
```

Configure the device-tunnel agent:

```text
TUNNEL_GATEWAY=<public-tunnel-host>:9443
TUNNEL_LOCAL_ADDR=127.0.0.1:8788
TUNNEL_DEVICE_ID=<enrolled-device-id>
TUNNEL_CA=/secure/tunnel/ca.pem
TUNNEL_CERT=/secure/tunnel/device.pem
TUNNEL_KEY=/secure/tunnel/device-key.pem
```

The agent initiates the connection. No router port-forward is required at the FadCam location.

### Public tunnel broker

Run the tunnel broker on the public edge host:

```text
TUNNEL_LISTEN=:9443
TUNNEL_PROXY_LISTEN=127.0.0.1:8785
DEVICE_REGISTRY_PATH=/secure/devices.json
TUNNEL_CA=/secure/tunnel/ca.pem
TUNNEL_CERT=/secure/tunnel/gateway.pem
TUNNEL_KEY=/secure/tunnel/gateway-key.pem
```

Port `9443` is the only tunnel ingress and must accept only certificates issued by the dedicated tunnel CA.

### TV 49 East catalog/relay

Run the catalog/relay on the same edge host as the tunnel broker, or on a host that can reach its private proxy:

```text
CATALOG_LISTEN=127.0.0.1:8790
TUNNEL_PROXY_BASE_URL=http://127.0.0.1:8785
CREATOR_REGISTRY_FILE=/secure/tv49east/creators.json
CREATOR_PUBLISH_KEY=<strong-random-publisher-secret>
RELAY_SIGNING_SECRET=<strong-random-relay-secret>
```

The relay now treats `source=fadcam` as a tunnel-backed origin. It does not require or accept a public FadCam origin URL. HLS playlists are rewritten so segments return through the same public relay and are capability-signed for a short lifetime.

## Publishing a FadCam channel

A trusted publisher registers the channel with the catalog using `POST /v1/creators/channels` and the publisher bearer secret.

Payload:

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

The `device_id` must match the enrolled tunnel identity and the tunnel broker registry must authorize the channel for that device. The FadCam stream itself remains local.

The public catalog then exposes only the logical relay URL:

```text
https://<public-tv49east-host>/v1/relay?id=creator-001
```

Consumers never receive `192.168.1.50:8080` or any other private origin address.

## Public Internet exposure

The `:8790` catalog/relay service should not be exposed directly. Put a public HTTPS reverse proxy in front of it and publish a hostname such as:

```text
https://stream.example.com
```

For a server with a public IP, DNS can point the hostname to that public IP and a reverse proxy such as Caddy can terminate HTTPS and forward to `127.0.0.1:8790`. Caddy supports automatic publicly trusted HTTPS for a configured public hostname.

If the server room is behind CGNAT, has no stable public IP, or inbound ports are undesirable, use an outbound public tunnel such as Cloudflare Tunnel. The connector runs inside the server room and establishes outbound connections; consumers use the public hostname and never need the private IP.

## Gate 1 verification

Do not call production complete until this exact chain succeeds:

1. FadCam local `/live.m3u8` works from the server-room host.
2. `server-tap` can read it on `127.0.0.1:8788`.
3. The device-tunnel agent is connected and the broker reports the device online.
4. The broker proxy can reach `/device/<device-id>/live.m3u8`.
5. The creator registry contains the same `device_id` and channel authorization.
6. `GET /v1/catalog` returns the FadCam channel with `source=fadcam` and `relay=true`.
7. `GET /v1/relay?id=<channel>` returns a rewritten M3U8, not the private origin URL.
8. The rewritten segment URL returns media through `/v1/relay-asset`.
9. A TV 49 East APK on a different Internet connection can play the public relay URL.

## Important scaling note

The tunnel solves reachability; it does not magically create unlimited bandwidth. Each HLS segment requested by a consumer ultimately traverses the production origin/tunnel path. For a small audience this is a clean architecture. For large worldwide audiences, put a media-aware CDN/distribution layer in front of the public HLS relay so repeated HLS segments can be distributed close to consumers while the private FadCam origin remains protected.

The existing FadCam server remains untouched: no public listener, no remote-control endpoint, and no user-supplied upstream URL is added to it.
