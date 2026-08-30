# Stream Gateway

User-facing boundary for streams discovered through `server-tap`.

## Responsibilities

1. Authenticate an authorized viewer.
2. Resolve a stable stream identifier to an upstream stream.
3. Proxy the HLS manifest and media resources.
4. Rewrite relative/absolute upstream URLs so private addresses never leak to clients.
5. Apply connection limits and basic rate limiting.
6. Expose health/metrics without exposing the upstream server directly.

## Safety boundary

The gateway must never expose the upstream `192.168.x.x`, `10.x.x.x`, `172.16/12`, localhost, or other private host address to an internet client.

Only the read-only streaming surface is exposed. Remote-control endpoints remain outside this gateway.

## Deployment

The gateway can run on a host that can reach the device running the existing server. If the device is behind NAT, the gateway requires a legitimate network path such as a VPN/tunnel or a port-forward configured outside the server implementation.
