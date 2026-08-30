# Server Tap

A read-only integration boundary around the existing FadCam HTTP streaming server.

## Invariants

- The existing server implementation is not modified.
- The tap performs GET-only reads against the configured upstream server.
- Control endpoints (POST/PUT/DELETE) are intentionally not proxied.
- The tap normalizes the upstream HLS manifest so clients do not receive private LAN addresses.
- Authentication for public access belongs at the gateway boundary.

## Data flow

`existing server :8080 -> server-tap -> stream-gateway -> authorized client`

Default upstream endpoints:

- `/live.m3u8`
- `/init.mp4`
- `/status`
- `/audio/volume`

The tap is deliberately independent of the server implementation. If the server changes internally but preserves these read endpoints, the adapter remains stable.
