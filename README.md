# TV 49 East

**TV 49 East is a unified TV streaming platform that brings together an Android/TV client, channel discovery and EPG sources, live media routing, and authorized FadCam-originated streaming.**

> **Status:** The repository defines the integration architecture and component boundaries. A physical end-to-end FadCam → TV screen stream is **not claimed as runtime-certified until it has been exercised on real devices**.

## What this repository actually contains

TV 49 East is a host/integration repository. It does **not** contain a replacement implementation of the FadCam server, and it does not pretend that all upstream TV components are one native codebase.

The project integrates independently maintained components as pinned Git submodules under `components/`:

```text
TV 49 East host repository
│
├── components/player          → Android TV/IPTV player source
├── components/channel-engine  → channel scheduling / TV engine source
├── components/media-router    → live media routing / proxy server
├── components/epg-engine      → EPG data source
├── components/playlist-client → Android playlist/client source
└── components/tv-client       → TV/IPTV client source
```

The exact upstream sources and branch selections are recorded in `.gitmodules`. The FadCam wiki is also retained as a documentation submodule at `docs/FadCam.wiki`.

## Architecture

The intended production data path is:

```text
                         LOCAL / AUTHORIZED NETWORK

 FadCam device                                             IPTV / EPG sources
 ┌─────────────────┐                                      ┌──────────────────┐
 │ Existing FadCam │                                      │ Authorized/public│
 │ HTTP/HLS server │                                      │ catalog sources  │
 │     :8080       │                                      └────────┬─────────┘
 └────────┬────────┘                                               │
          │ HLS                                                     │ discovery
          ▼                                                         ▼
   ┌───────────────┐                                      ┌──────────────────┐
   │  server-tap   │                                      │ stream-catalog / │
   │ narrow source │                                      │ EPG / playlists  │
   │    adapter    │                                      └────────┬─────────┘
   └───────┬───────┘                                               │
           │                                                        │ metadata
           └──────────────────┐                     ┌───────────────┘
                              ▼                     ▼
                       ┌────────────────────────────────┐
                       │       stream-gateway /         │
                       │       media-router boundary    │
                       │                                │
                       │ auth → authorization → relay  │
                       │ HLS manifest / init / segments │
                       └───────────────┬────────────────┘
                                       │ authorized HTTPS/HLS
                                       ▼
                         ┌─────────────────────────────┐
                         │        TV 49 East client    │
                         │                             │
                         │ channel selection / handoff │
                         │              ↓              │
                         │       playback engine       │
                         │              ↓              │
                         │       video surface         │
                         └──────────────┬──────────────┘
                                        │
                                        ▼
                                  PHYSICAL TV
```

### Important boundary

The existing FadCam server remains a **protected source system**. Integration is performed around it. The project must not silently replace its routes, storage, recording behavior, or control endpoints.

A private FadCam address such as `192.168.100.5:8080` is **not a public TV playback endpoint**. A gateway deployed outside that LAN requires an authorized network path such as a VPN, secure tunnel, or controlled forwarding mechanism.

## Component map

### `components/player`

Pinned Android TV/IPTV player source. This is one of the receiver-side building blocks; it is not proof by itself that the final application is connected to the FadCam gateway.

### `components/channel-engine`

Pinned channel/scheduling engine source used as part of the TV platform integration.

### `components/media-router`

Pinned media-routing/proxy source. This is the media infrastructure component used for live routing and playback-server responsibilities.

### `components/epg-engine`

Pinned EPG source. EPG metadata is a discovery/program-guide concern and is kept separate from stream authorization.

### `components/playlist-client`

Pinned Android playlist/client source used for channel discovery and playback integration.

### `components/tv-client`

Pinned TV/IPTV client source used as another receiver-side component. Its presence does not automatically establish an end-to-end FadCam playback path; that connection must be demonstrated through the actual handoff and playback code.

### `docs/FadCam.wiki`

Documentation mirror for the upstream FadCam project. It is documentation, not the FadCam server implementation used by this repository.

## FadCam integration

The FadCam path is intentionally narrow:

```text
Existing FadCam HTTP/HLS server
            ↓
        server-tap
            ↓
 authorized transport / network path
            ↓
     streaming gateway
            ↓
 authorized HLS manifest
            ↓
       TV client
```

The integration layer should consume FadCam's existing HTTP/HLS output rather than modifying FadCam's server implementation.

### What `server-tap` is responsible for

- reaching the existing FadCam HTTP/HLS source from an authorized network position;
- reading the source HLS manifest and media resources;
- exposing a narrow integration boundary to the streaming layer;
- avoiding remote-control mutations and unrelated FadCam server behavior.

### What it must not do

- replace the FadCam server;
- expose the FadCam private LAN address to TV clients;
- turn an unauthenticated source into a public relay;
- provide unauthorized camera/control access.

## Gateway and media routing

The protected streaming boundary is responsible for separating **viewer access** from the private upstream source.

Conceptually:

```text
Viewer / TV client
       ↓
authentication
       ↓
stream authorization
       ↓
stream resolution
       ↓
private upstream
       ↓
HLS proxy / rewrite
       ↓
manifest + init + segments
```

For FadCam-originated playback, the gateway must hide the private upstream address and expose only an authorized client-facing stream.

No transcoding is required for the initial relay path when the authorized HLS payload is already compatible with the receiver.

## Channel discovery is not authorization

The project deliberately separates discovery from redistribution rights.

```text
Public/catalog metadata
        ↓
 discovery
        ↓
 normalized channel data
        ↓
 authorization decision
        ↓
 playback URL
```

A public IPTV playlist or EPG entry does **not** by itself authorize TV 49 East to redistribute the associated stream. External sources must be consumed directly where permitted, or explicitly onboarded as authorized relay sources.

## Receiver-side playback proof

The final TV path must be traced through the actual receiver code. The repository should not be considered end-to-end complete merely because an ExoPlayer/Media3 dependency, a player component, or a TV APK exists.

The required receiver chain is:

```text
TV application startup
        ↓
receiver activity / client initialization
        ↓
channel selection or server-side handoff
        ↓
authorized HTTPS playback URL
        ↓
MediaItem / media source
        ↓
Media3 / ExoPlayer playback
        ↓
PlayerView / Surface
        ↓
Android video renderer
        ↓
physical TV display
```

The critical forensic question is **URL provenance**:

```text
GOOD:
TV client → authorized TV 49 East/gateway URL → HLS → video surface

BAD:
TV client → http://192.168.x.x:8080/... → FadCam directly

UNPROVEN:
TV client → hard-coded or unrelated IPTV URL
```

The repository must only claim the first path when the actual code and runtime evidence establish it.

## End-to-end certification gate

Source-level presence is not physical-TV certification.

A real Gate 1 certification requires all of the following:

```text
[01] FadCam is running and producing live video
[02] FadCam HTTP/HLS source is reachable from the authorized integration position
[03] server-tap successfully reads the live source
[04] the authorized transport path carries the source to the gateway
[05] gateway authenticates the viewer/client
[06] gateway authorizes the requested FadCam stream
[07] gateway returns a valid HLS manifest
[08] HLS init data and media segments successfully pass through the gateway
[09] TV client receives the gateway playback URL
[10] Media3/ExoPlayer accepts and prepares the HLS source
[11] ExoPlayer reaches a playing state
[12] the active player is attached to the visible video surface
[13] decoded video is visibly rendered on the physical TV
```

Only after step 13 has been demonstrated should the project documentation state that **FadCam → TV screen streaming is end-to-end operational**.

## Repository integration model

The repository deliberately uses submodules instead of copying upstream projects into a single artificial source tree.

`.gitmodules` currently defines:

```text
components/player          → opentvproject/opentv
components/channel-engine  → ErsatzTV/legacy
components/media-router    → Trendyzima/mediamtx
components/epg-engine      → iptv-org/epg
components/playlist-client → oxyroid/M3UAndroid
components/tv-client       → Davidona/StreamVault-IPTV

docs/FadCam.wiki           → anonfaded/FadCam.wiki
```

Each component retains its own upstream history, build assumptions, licensing, and attribution. Integration work belongs at the boundaries: APIs, stream URLs, playlists/EPG, authentication, health/observability, and deployment configuration.

## Repository structure

```text
.
├── components/
│   ├── player/             # Android TV/IPTV player component
│   ├── channel-engine/     # channel/scheduling component
│   ├── media-router/       # live media routing/proxy component
│   ├── epg-engine/         # EPG component
│   ├── playlist-client/    # playlist/client component
│   └── tv-client/          # TV/IPTV client component
│
├── docs/
│   └── FadCam.wiki/        # FadCam documentation submodule
│
├── ARCHITECTURE.md         # non-negotiable architecture boundaries
├── INTEGRATION.md          # component integration rules
├── .gitmodules             # authoritative submodule definitions
└── README.md               # project overview and certification criteria
```

## Development principles

1. **Freeze the existing FadCam server.** Integration belongs around it.
2. **Keep private FadCam addressing private.** Do not put LAN source URLs in the TV client.
3. **Separate discovery from authorization.** Catalog metadata is not permission to relay.
4. **Use an explicit protected streaming boundary.** Authentication and authorization occur before protected playback.
5. **Treat upstream components as components.** Do not claim they are one native implementation when they are pinned submodules.
6. **Trace the real receiver path.** A player dependency is not a playback proof.
7. **Prefer HLS passthrough.** Avoid unnecessary transcoding for the initial authorized path.
8. **Fail closed.** Missing authorization, unavailable upstreams, or invalid playback configuration must not become open relay behavior.
9. **Separate source evidence from runtime evidence.** Code inspection can prove wiring; only a live device test can prove physical-screen playback.
10. **Do not claim end-to-end streaming until it is actually demonstrated.**

## Documentation

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — platform boundaries and responsibilities
- [`INTEGRATION.md`](INTEGRATION.md) — component integration model
- [`.gitmodules`](.gitmodules) — pinned upstream component definitions
- [`docs/FadCam.wiki`](docs/FadCam.wiki) — FadCam documentation submodule

## Responsible use

Only stream, relay, record, or redistribute content for which the operator has the necessary rights and authorization. A public IPTV catalog entry does not by itself grant redistribution rights.
