# Hardened device-to-gateway tunnel

This component creates a **reverse, outbound-only mTLS TCP tunnel** from the device-side server tap to a gateway. The existing FadCam HTTP server is not changed and port `8080` is not exposed by this component.

## Topology

```text
FadCam server :8080
       |
       | existing local boundary
       v
server-tap :8786
       |
       | device-tunnel agent -- outbound TLS 1.3 + client certificate
       |
       v
public gateway :9443
       |
       | loopback only
       v
127.0.0.1:8786
       |
       v
stream-gateway
       |
       v
authorized users
```

## Security properties

- TLS 1.3 minimum.
- Mutual certificate authentication; both peers require a certificate issued by the configured CA.
- Device initiates the connection. No inbound connection to the device is required.
- Gateway's forwarded listener binds to `127.0.0.1` by default and is not a public proxy.
- The tunnel has a fixed destination: the local `server-tap` address. It does not accept a user-supplied host or port.
- Each tunnel connection serves one TCP request and is discarded afterward.
- A small bounded idle pool prevents unbounded tunnel creation.
- Handshake and local-connect timeouts limit resource exhaustion.
- The protocol is deliberately tiny: `TV49-TUNNEL/1`, `OK`, `START`, then raw bytes.

## Deployment

Run the agent on the same device/network namespace as `server-tap`. Run the gateway listener on the public gateway host. Configure `stream-gateway` to use the gateway's loopback forward address, for example `http://127.0.0.1:8786`.

Do **not** place the device's private `192.168.x.x:8080` URL in public configuration or playlists.

## Certificates

Use a private CA dedicated to this tunnel. Issue separate certificates for each device and the gateway. Keep private keys outside the repository. Certificate rotation should be handled by deployment automation; never commit private keys.

The tunnel is transport security only. Authentication and authorization of end users remain the responsibility of `stream-gateway`.

## Operational rule

The FadCam server remains untouched. The only upstream endpoint opened by the agent is the local server-tap listener.
