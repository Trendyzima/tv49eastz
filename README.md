# TV 49 East

**Secure TV streaming platform connecting FadCam-originated live video and authorized IPTV sources to an Android TV receiver.**

## Production downloads

### Android APKs

Once a versioned production tag (`v*`) passes the production verification job, GitHub Actions publishes the signed binaries as a GitHub Release.

| Application | Direct APK |
|---|---|
| **FadCam** | [Download FadCam.apk](https://github.com/Trendyzima/tv49eastz/releases/latest/download/FadCam.apk) |
| **TV 49 East** | [Download TV49East.apk](https://github.com/Trendyzima/tv49eastz/releases/latest/download/TV49East.apk) |

The production release also publishes the FadCam AAB, SHA-256 checksums, and release-certificate information.

> **Download status:** these URLs are intentionally release-backed. They become live only after the first successful versioned production release is published. Do not treat a missing `latest` release as a valid APK.

## Production release process

```text
version tag vX.Y.Z
        ↓
Production verification
        ├── Go race tests
        ├── Go vet
        ├── Android unit tests
        ├── patched Media3 substitution check
        └── Android release lint
                ↓
Signed release build
        ├── FadCam.apk
        ├── TV49East.apk
        └── FadCam.aab
                ↓
SHA-256 checksums
        ↓
GitHub artifact upload
        ↓
GitHub Release publication
        ↓
README direct-download links
```

The release workflow requires the repository's Android release-signing secrets and fails closed if they are absent. It also pins the certified patched Media3 checkout before building.

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
        │ + creator data   │
        └──────────────────┘
```

The existing FadCam server is a protected source system. TV 49 East integrates around it rather than replacing its server, routes, storage, recording behavior, or control endpoints.

## Repository components

### `app/`

The FadCam Android application/source. FadCam remains the source publisher for FadCam-originated video.

### `server-tap/`

Go adapter for the existing FadCam HTTP/HLS source. It is a narrow integration boundary rather than a replacement server or remote-control interface.

### `device-tunnel/`

Authenticated device-to-gateway transport containing the device agent, gateway, and protocol layers.

### `stream-gateway/`

Secure public streaming boundary handling viewer authentication, authorization, stream resolution, private-upstream isolation, HLS manifest rewriting, and HLS resource proxying.

Documented streaming routes include:

```text
GET /stream/{id}/index.m3u8
GET /stream/{id}/init.mp4
GET /stream/{id}/segment/{name}
GET /health
```

### `stream-catalog/`

Discovery and catalog service. Discovery is kept separate from authorization; public IPTV entries are not automatically authorized for redistribution.

### `tv-receiver/`

Standalone Android TV application with Media3/ExoPlayer playback and a video surface.

## TV playback chain

The receiver's application-side playback path is:

```text
MainActivity.onCreate()
        ↓
buildUi()
        ↓
PlayerView created
        ↓
channel / handoff selected
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

The receiver also keeps the playback screen awake.

## HLS URL provenance

The normal catalog path is server-side:

```text
TV receiver
    ↓
CatalogClient
    ↓
configured HTTPS catalog
    ↓
server-side channel metadata
    ↓
relay=true entries only
    ↓
configured TV East origin
    ↓
Media3
```

The receiver supports explicit FadCam and TV East handoffs, but production playback URLs must point at the authorized TV East/gateway surface, never at a private FadCam LAN address.

## Target end-to-end streaming path

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

A direct path such as `TV → http://192.168.x.x:8080/...` is not the production architecture because it bypasses the security boundary.

## Security boundaries

- Existing FadCam server remains unchanged.
- Private FadCam addressing stays behind the tunnel/gateway boundary.
- Viewers are authenticated before protected playback.
- Requested streams are authorized before proxying.
- HLS manifests and media resources remain behind the streaming boundary.
- Catalog discovery is separate from redistribution authorization.
- Client-facing catalog configuration uses HTTPS.
- The TV receiver rejects non-HTTPS playback URLs.
- Device identity and stream authorization are separate from catalog discovery.
- Certified Media3 dependencies are required at build time.

## Build structure

Root Gradle modules:

```text
:app
:tv-receiver
```

Go modules:

```text
server-tap/go.mod
device-tunnel/go.mod
stream-catalog/go.mod
stream-gateway/go.mod
```

The TV receiver supports API 24+ and targets API 36 with Java 17.

### Patched Media3

The Android build requires the pinned patched Media3 checkout:

```properties
media3.patched.path=/tmp/media3-patched
```

The production workflow fetches the pinned commit and fails closed if the expected patched Media3 sources are absent or dependency substitution is not proven.

## Production certification

Source-level wiring is not the same as physical-TV runtime certification.

The production runtime certification must prove:

```text
[01] FadCam produces live video
[02] server-tap reads the live source
[03] device-tunnel carries the authorized path
[04] gateway authenticates the viewer
[05] gateway authorizes the stream
[06] gateway emits a valid HLS manifest
[07] HLS init data and segments work through the gateway
[08] TV receiver obtains the gateway URL
[09] Media3 prepares the HLS source
[10] ExoPlayer reaches a playing state
[11] PlayerView is attached to the active player
[12] decoded video reaches the Android TV surface
[13] video is visibly rendered on the physical TV
```

Until those runtime checks are executed against a real TV/device, the accurate status is **source-level wired and production-build capable**, not physically certified.

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
└── README.md               # project architecture, releases, certification
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
