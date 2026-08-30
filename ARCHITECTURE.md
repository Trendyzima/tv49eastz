# Platform architecture

## Non-negotiable boundary

The existing FadCam server room is a protected source system. This repository does not modify its implementation, routes, storage, recording behavior, or control endpoints as part of the streaming integration.

## Layers

```text
Existing server :8080
        |
        | GET-only
        v
   server-tap
        |
        | normalized stream/status data
        v
  stream-gateway
        |
        | authenticated public HLS
        v
     clients
```

## Responsibilities

### Existing server
Source of truth for the existing server's stream and status surfaces. It remains untouched by the integration layer.

### server-tap
A narrow adapter that reaches the existing HTTP server from a network position where the private address is routable. It reads HLS, initialization data, segments, and status. It does not issue remote-control mutations.

### stream-gateway
The user-facing boundary. It authenticates viewers, hides private upstream addressing, rewrites HLS references, proxies live media, and applies connection/rate limits.

### Client
The Android/TV/mobile/web client consumes stable gateway URLs and never needs direct access to the device's private LAN address.

## Remote access requirement

A gateway cannot reach a private address such as `192.168.100.5` from the public internet by itself. The deployment must provide an authorized network path (for example, a VPN, secure tunnel, or controlled port-forward) between the gateway and the device. This networking layer is external to the existing server implementation.

## Design goal

Preserve the existing server exactly as the source system while adding a replaceable integration boundary around it. No transcoding is required for the initial path; HLS payloads should be relayed as-is whenever possible.
