# Platform architecture

## Non-negotiable boundaries

The existing FadCam server room is a protected source system. This repository does not modify its implementation, routes, storage, recording behavior, or control endpoints as part of the streaming integration.

The external IPTV catalog is a **discovery source**, not blanket authorization to redistribute every listed stream. TV 49 East may relay only channels for which the operator has the necessary permission. Other catalog entries are consumed directly from their HTTPS source when available.

## Layers

```text
                         +----------------------+
                         | iptv-org public       |
                         | channel catalog       |
                         +----------+-----------+
                                    |
                                    | HTTPS M3U discovery
                                    v
                              stream-catalog
                                    |
                         normalized channel JSON
                                    |
                                    v
FadCam :8080 -> server-tap -> stream-gateway -> TV 49 East client
                                    |
                                    +--> authorized external relay sources only
                                    |
                                    +--> direct HTTPS public/authorized sources
```

## Responsibilities

### Existing FadCam server
Source of truth for the FadCam stream and status surfaces. It remains untouched by the integration layer.

### server-tap
A narrow adapter that reaches the existing HTTP server from a network position where the private address is routable. It reads HLS, initialization data, segments, and status. It does not issue remote-control mutations.

### stream-catalog
Refreshes the iptv-org M3U playlist, validates that imported playback URLs are HTTPS, extracts channel metadata, filters unsafe entries, and exposes a cached JSON catalog. It retains the last known-good snapshot during upstream failures.

### stream-gateway
The user-facing secure boundary for FadCam and separately authorized relay sources. It authenticates viewers, hides private upstream addressing, rewrites HLS references, proxies live media, and applies connection/rate limits.

### Client
The Android/TV/mobile/web client consumes stable TV 49 East channel metadata. FadCam-produced channels remain featured first; catalog channels provide the variety layer.

## Remote access requirement

A gateway cannot reach a private address such as `192.168.100.5` from the public internet by itself. The deployment must provide an authorized network path (for example, a VPN, secure tunnel, or controlled port-forward) between the gateway and the device. This networking layer is external to the existing server implementation.

## Relay policy

The catalog deliberately emits `relay: false` for iptv-org entries. This prevents a public playlist from silently becoming a redistribution service. To add a relay source, it must be explicitly onboarded as an authorized source and then routed through the gateway's existing authorization/session machinery.

## Design goal

Preserve the existing FadCam server exactly while adding a replaceable channel-discovery layer and a controlled streaming boundary. No transcoding is required for the initial path; authorized HLS payloads should be relayed as-is whenever possible.
