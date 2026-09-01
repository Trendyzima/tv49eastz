# Stream Gateway

The gateway is the public-facing half of the read-only streaming bridge. It talks only to the configured `server-tap`/device-tunnel proxy; it never accepts an arbitrary upstream URL and never exposes the FadCam address in HLS responses.

## Gate 1 flow

`FadCam :8080` → `server-tap :8788` → outbound mTLS device tunnel → gateway proxy `127.0.0.1:8785` → `stream-gateway :8787` → authorized HLS client

The FadCam Server Room is not modified by this service. The aggregated IPTV catalog is a separate source path and does not traverse this gateway.

## Start

The gateway must consume the device-tunnel gateway's loopback HTTP proxy, **not** the FadCam LAN address and not the device-side server-tap listener:

```sh
GATEWAY_API_KEY='replace-with-a-long-random-secret' \
GATEWAY_CAPABILITY_KEY='replace-with-a-different-random-secret' \
TAP_UPSTREAM='http://127.0.0.1:8785' \
GATEWAY_TLS_CERT_FILE='/etc/tv49eastz/gateway.pem' \
GATEWAY_TLS_KEY_FILE='/etc/tv49eastz/gateway-key.pem' \
GATEWAY_CLIENT_CA_FILE='/etc/tv49eastz/ca.pem' \
go run .
```

The device side keeps the existing FadCam server and tap boundary:

```text
TAP_LISTEN=127.0.0.1:8788
TAP_UPSTREAM=http://<fadcam-local-ip>:8080
TUNNEL_LOCAL_ADDR=127.0.0.1:8788
```

The gateway-side tunnel proxy is:

```text
TUNNEL_PROXY_LISTEN=127.0.0.1:8785
```

Create a short-lived stream session with JSON — **GET is intentionally rejected**:

```sh
curl -X POST \
  -H 'Authorization: Bearer replace-with-a-long-random-secret' \
  -H 'Content-Type: application/json' \
  -d '{"channel_id":"camera","stream_id":"fadcam-device-stream"}' \
  https://gateway.example/v1/session
```

The response contains an opaque `/stream/<session>/index.m3u8` URL. HLS media requests use session-bound capability URLs, so the bearer credential is not embedded in the playlist.

Stop-TV should immediately revoke the session:

```sh
curl -X DELETE \
  -H 'Authorization: Bearer replace-with-a-long-random-secret' \
  https://gateway.example/v1/session/<session-id>
```

## Security properties

- Gateway credentials are server-side secrets; never embed `GATEWAY_API_KEY` in an Android APK.
- Sessions are authenticated, device-bound, and expire after 15 minutes.
- DELETE revocation invalidates the session immediately and releases its slot.
- Active sessions are capped by `MAX_SESSIONS`.
- Requests are rate-limited per source address.
- Only GET is allowed on stream resources.
- The gateway follows no upstream redirects.
- HLS resource references must be relative and are rewritten to opaque gateway URLs.
- Absolute/external URLs and traversal paths are rejected.
- The FadCam LAN address is never copied into the client-facing manifest.
- `/health` is intentionally unauthenticated for infrastructure probes; `/metrics` and session lifecycle endpoints require the bearer credential.
- The transport tunnel uses mutual TLS; end-user authorization remains the responsibility of `stream-gateway`.

This is an integration gateway, not an Internet-facing deployment recipe. Put it behind the intended TLS/edge deployment and firewall policy before public exposure.
