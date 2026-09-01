# TV 49 East

**Secure TV streaming platform connecting FadCam-originated live video and authorized IPTV sources to an Android TV receiver.**

> **Certification status:** the repository contains the FadCam integration layer, `server-tap`, `device-tunnel`, `stream-gateway`, `stream-catalog`, and a standalone `tv-receiver` Android application. The receiver is explicitly wired to Media3/ExoPlayer and `PlayerView`. The source code demonstrates the application-side playback chain. A physical-TV runtime test is still required before claiming that the complete FadCam → TV screen path has been operationally certified.

## Architecture

```text
 FadCam phone
 ┌─────────────────┐
 │ Existing HTTP   │
 │ server :8080    │
 └────────┬────────┘
          │ HLS
          ▼
 ┌─────────────────┐
 │   server-tap    │
 └────────┬────────┘
          │
          ▼
 ┌─────────────────┐
 │  device-tunnel  │
 └────────┬────────┘
          │ authenticated path
          ▼
 ┌────────────────────────────────┐
 │        stream-gateway          │
 │ TLS/mTLS • identity • auth     │
 │ authorization • HLS proxy      │
 └───────────────┬────────────────┘
                 │ authorized HLS
                 ▼
 ┌────────────────────────────────┐
 │       TV 49 East receiver      │
 │                                │
 │ catalog / handoff → HLS URL    │
 │              ↓                 │
 │         MediaItem              │
 │              ↓                 │
 │        ExoPlayer/Media3        │
 │              ↓                 │
 │          PlayerView            │
 │              ↓                 │
 │        TV video surface        │
 └────────────────────────────────┘

                 ▲
                 │ catalog metadata / authorized relay
                 │
        ┌────────┴─────────┐
        │  stream-catalog  │
        │ IPTV discovery   │
        │ + creator data  │
        └──────────────────┘
```

The existing FadCam server is a protected source system. TV 49 East is designed to integrate around it rather than replace its server, routes, storage, recording behavior, or control endpoints.

The TV client is not supposed to connect directly to a private address such as `192.168.x.x:8080`. The tunnel and gateway provide the controlled path that hides the private source address.

## Repository components

### `app/`

The FadCam Android application/source. FadCam remains the source publisher for FadCam-originated video.

### `server-tap/`

Go adapter for the existing FadCam HTTP/HLS source. It is a narrow integration boundary rather than a replacement server or remote-control interface.

Important files:

```text
server-tap/main.go
server-tap/main_test.go
server-tap/contract.md
server-tap/README.md
```

### `device-tunnel/`

Authenticated device-to-gateway transport. The repository contains separate `agent`, `gateway`, and `protocol` areas plus example configuration.

### `stream-gateway/`

Secure public streaming boundary. It handles viewer authentication, authorization, stream resolution, private-upstream isolation, HLS manifest rewriting, and proxying of HLS initialization data and segments.

Documented routes:

```text
GET /stream/{id}/index.m3u8
GET /stream/{id}/init.mp4
GET /stream/{id}/segment/{name}
GET /health
```

Gateway viewer flow:

```text
authenticate
   ↓
request stable stream URL
   ↓
resolve authorized stream to tap
   ↓
tap reads existing FadCam server
   ↓
return client-safe HLS manifest
   ↓
proxy subsequent HLS resources
```

### `stream-catalog/`

Discovery and catalog service. It normalizes channel metadata and keeps discovery separate from authorization. Public IPTV entries are not automatically authorized for redistribution.

### `tv-receiver/`

Standalone Android TV application with its own manifest, source, tests, Media3 dependencies, and build configuration.

```text
tv-receiver/
├── build.gradle.kts
├── proguard-rules.pro
└── src/
    ├── main/
    │   ├── AndroidManifest.xml
    │   └── java/com/fadcam/tv/
    │       ├── MainActivity.java
    │       ├── CatalogClient.java
    │       ├── ChannelStore.java
    │       ├── FadCamHandoffActivity.java
    │       └── FadCamHandoffVerifier.java
    ├── test/
    └── androidTest/
```

## TV playback: source-level proof

`MainActivity` contains the actual receiver-to-player chain:

```text
MainActivity.onCreate()
        ↓
buildUi()
        ↓
PlayerView created
        ↓
channel selected / handoff received
        ↓
startPlayback(url, channelName)
        ↓
ExoPlayer.Builder(this).build()
        ↓
playerView.setPlayer(player)
        ↓
MediaItem.fromUri(url)
        ↓
player.setMediaItem(...)
        ↓
player.prepare()
        ↓
player.play()
        ↓
Android video surface / TV display
```

The receiver also calls `setKeepScreenOn(true)` for the playback UI.

### HLS URL provenance

The normal catalog path is server-side:

```text
TV receiver
    ↓
CatalogClient
    ↓
GET {configured HTTPS catalog}/v1/catalog
    ↓
server-side channel metadata
    ↓
relay=true entries only
    ↓
/v1/relay?id=...
    ↓
configured TV East origin
    ↓
Media3
```

`CatalogClient` rejects non-HTTPS catalog origins and ignores catalog entries that are not marked `relay=true`. It therefore does not directly feed arbitrary upstream IPTV URLs into the normal receiver catalog path.

The receiver also supports explicit `fadcam://stream?...` and `tv49east://channel?...` handoffs. These require HTTPS playback URLs. Production handoffs should point at the authorized TV East/gateway surface, never a private FadCam LAN address.

## FadCam → TV streaming path

The target end-to-end route is:

```text
FadCam HTTP server
      ↓
server-tap
      ↓
device-tunnel
      ↓
stream-gateway
      ↓
authorized HLS manifest
      ↓
TV 49 East receiver
      ↓
Media3 / ExoPlayer
      ↓
PlayerView
      ↓
TV video surface
      ↓
physical TV
```

This is deliberately different from a direct connection such as:

```text
TV → http://192.168.x.x:8080/...
```

The direct private-address path bypasses the intended security boundary and is not the target architecture.

## IPTV path

```text
IPTV discovery source
       ↓
stream-catalog
       ↓
normalized metadata
       ↓
explicitly authorized relay
       ↓
stream-gateway
       ↓
TV receiver
```

A public playlist is discovery data, not blanket permission to redistribute every listed stream.

## Security boundaries

- Existing FadCam server remains unchanged.
- Private FadCam addressing stays behind the tunnel/gateway boundary.
- Viewers are authenticated before protected playback.
- Requested streams are authorized before proxying.
- HLS manifests and subsequent media resources remain behind the streaming boundary.
- Catalog discovery is separate from redistribution authorization.
- Client-facing catalog configuration must use HTTPS.
- The TV receiver rejects non-HTTPS playback URLs.
- Device identity and stream authorization are separate from catalog discovery.
- Required certified Media3 dependencies are fail-closed at build time.

The gateway tree contains TLS, device-registry, persistence, capability-security, and related tests.

## Build structure

Root Gradle modules:

```text
:app
:tv-receiver
```

The TV receiver targets Android API 36, supports API 24+, targets API 36, and uses Java 17. It depends on Media3 ExoPlayer/UI/session components.

Go modules:

```text
server-tap/go.mod
device-tunnel/go.mod
stream-catalog/go.mod
stream-gateway/go.mod
```

### Patched Media3 requirement

The root `settings.gradle.kts` requires the pinned patched Media3 checkout before the Android build can proceed. Default:

```text
/tmp/media3-patched
```

Override with:

```properties
media3.patched.path=/path/to/media3-patched
```

The build intentionally fails closed if that checkout is absent, preventing a silent fallback to unqualified upstream Media3 artifacts.

## End-to-end certification standard

The repository distinguishes **source-level wiring** from **runtime certification**.

Source-level evidence establishes:

```text
TV receiver
 → MainActivity
 → PlayerView
 → ExoPlayer
 → MediaItem.fromUri(HLS URL)
 → prepare()
 → play()
```

and the server-side architecture establishes:

```text
FadCam
 → server-tap
 → device-tunnel
 → stream-gateway
 → authorized HLS
```

A real physical-TV certification must prove all edges:

```text
[01] FadCam produces live video
[02] server-tap reads the live source
[03] device-tunnel carries the authorized path
[04] gateway authenticates the viewer
[05] gateway authorizes the stream
[06] gateway emits a valid HLS manifest
[07] init data and segments are retrievable through gateway
[08] TV receiver obtains the gateway URL
[09] Media3 prepares the HLS source
[10] ExoPlayer reaches a playing state
[11] PlayerView is attached to the active player
[12] decoded video reaches the Android TV surface
[13] video is visibly rendered on the physical TV
```

Until that runtime test is executed, the accurate status is **source-level wired and ready for end-to-end certification**, not “physically proven on a TV.”

## Repository map

```text
.
├── app/                    # FadCam Android source/application
├── tv-receiver/            # Android TV playback application
├── server-tap/             # FadCam HTTP/HLS adapter
├── device-tunnel/          # authenticated device transport
├── stream-gateway/         # secure HLS gateway
├── stream-catalog/         # catalog/discovery service
├── ARCHITECTURE.md         # system boundaries
├── settings.gradle.kts     # Android modules + pinned Media3 build
└── README.md               # project architecture and certification status
```

## Design rules

1. **Freeze FadCam's existing server.** Integration belongs around it.
2. **Never make the TV client depend on the FadCam private LAN address.**
3. **Separate discovery from authorization.**
4. **Use the gateway as the protected playback boundary.**
5. **Keep the TV receiver as a real Media3 playback application.**
6. **Fail closed on missing secure transport or certified Media3 dependencies.**
7. **Distinguish source evidence from runtime evidence.**
8. **Do not claim physical-TV streaming until the live path has actually been exercised.**

## Documentation

- `ARCHITECTURE.md` — platform boundaries and responsibilities
- `server-tap/README.md` — FadCam source adapter
- `server-tap/contract.md` — tap contract
- `device-tunnel/README.md` — authenticated transport
- `stream-gateway/README.md` — gateway implementation
- `stream-gateway/API.md` — gateway streaming routes
- `stream-catalog/README.md` — catalog implementation

## Responsible use

Only stream, relay, record, or redistribute content for which the operator has the necessary rights and authorization. A public IPTV catalog entry does not by itself grant redistribution rights.
