# Hardened device-to-gateway tunnel

This component creates a **reverse, outbound-only mTLS TCP tunnel** from the device-side server tap to a gateway. The existing FadCam HTTP server is not changed and its local port `8080` is never exposed by this component.

## Gate 1 local topology

```text
FadCam local HTTP server
       |
       | existing local/LAN boundary
       | TAP_UPSTREAM=http://<fadcam-local-ip>:8080
       v
server-tap 127.0.0.1:8788
       |
       | device-tunnel agent
       | outbound TLS 1.3 + client certificate
       v
TUNNEL_GATEWAY:<9443>
       |
       | authenticated device tunnel
       v
TUNNEL_PROXY_LISTEN=127.0.0.1:8785
       |
       v
stream-gateway
       |
       v
authorized Android clients
```

**No VPS is required for the FadCam endpoint.** The FadCam server remains on its existing local IP/port. The tunnel agent connects only to `server-tap`; it never publishes the FadCam address.

The existing aggregated IPTV catalog is a separate source path and does not need to traverse this FadCam tunnel.

## Security properties

- TLS 1.3 minimum.
- Mutual certificate authentication; both peers require a certificate issued by the configured CA.
- Device initiates the connection. No inbound connection to the device is required.
- Gateway's forwarded HTTP listener binds to `127.0.0.1` by default and is not a public media proxy.
- The tunnel has a fixed destination: the local `server-tap` address. It does not accept a user-supplied host or port.
- Each tunnel connection serves one TCP request and is discarded afterward.
- A small bounded idle pool prevents unbounded tunnel creation.
- Handshake and local-connect timeouts limit resource exhaustion.
- The protocol is deliberately tiny: `TV49-TUNNEL/1`, `OK`, `START`, then raw bytes.

## Gate 1 configuration

The device side must use the same listener address as `server-tap`:

```text
TAP_LISTEN=127.0.0.1:8788
TUNNEL_LOCAL_ADDR=127.0.0.1:8788
```

Point `TAP_UPSTREAM` at the **actual FadCam local HTTP server**, for example:

```text
TAP_UPSTREAM=http://<fadcam-local-ip>:8080
```

On the gateway host:

```text
TUNNEL_LISTEN=:9443
TUNNEL_PROXY_LISTEN=127.0.0.1:8785
```

Configure `stream-gateway` to use:

```text
TUNNEL_PROXY_BASE_URL=http://127.0.0.1:8785
TAP_UPSTREAM=http://127.0.0.1:8785
```

The stream gateway must never be configured with the FadCam LAN URL. It consumes the gateway's local tunnel proxy instead.

## Certificates

Use a private CA dedicated to this tunnel. Issue separate certificates for each device and the gateway. Keep private keys outside the repository. Certificate rotation should be handled by deployment automation; never commit private keys.

The tunnel is transport security only. Authentication and authorization of end users remain the responsibility of `stream-gateway`.

## Operational rule

The FadCam server remains untouched. The only upstream endpoint opened by the agent is the local server-tap listener.
