# TV 49 East Channel Catalog + Relay

This service is the server-side distribution layer for the standalone TV 49 East Android receiver.

## Sources

1. **FadCam creators** — authenticated publishers register their authorized HTTPS HLS endpoint. These channels are returned first and are labeled `TV East`.
2. **iptv-org** — the public M3U catalog is refreshed periodically. Imported entries are normalized to HTTPS and exposed to the receiver only through the TV 49 East relay.

The Android client never needs to scrape the iptv-org playlist and never receives an upstream IPTV URL.

## Runtime

Required:

- `RELAY_SIGNING_SECRET` — high-entropy secret used to sign short-lived HLS asset capabilities.

Optional:

- `CREATOR_PUBLISH_KEY` — bearer token required for creator registration/removal.
- `CREATOR_REGISTRY_FILE` — durable creator registry path; default `./data/creators.json`.
- `IPTV_ORG_PLAYLIST` — default `https://iptv-org.github.io/iptv/index.m3u`.
- `CATALOG_REFRESH_MINUTES` — default `30`.
- `CATALOG_TIMEOUT_SECONDS` — default `20`.
- `CATALOG_MAX_BYTES` — default `33554432`.
- `CATALOG_LISTEN` — default `:8790`.

Put the service behind an HTTPS reverse proxy/load balancer before exposing it publicly. Do not expose the Go listener directly to the Internet without TLS and normal edge rate limiting.

## API

### Public catalog

`GET /v1/catalog`

Optional query parameters:

- `country=KE`
- `group=News`
- `q=BBC`

The response contains relay URLs such as `/v1/relay?id=<channel-id>`.

### Relay

`GET /v1/relay?id=<channel-id>`

Only channels in the server-side catalog/creator registry can be relayed. HLS playlists are rewritten so segments, keys, maps and other URI-bearing resources receive short-lived HMAC capabilities.

### Creator publishing

`GET /v1/creators/channels`

`POST /v1/creators/channels` with `Authorization: Bearer <CREATOR_PUBLISH_KEY>`

Example payload:

```json
{
  "id": "creator-001",
  "name": "Community News",
  "owner": "Creator Name",
  "country": "KE",
  "language": "eng",
  "logo": "https://example.com/logo.png",
  "stream": "https://creator.example/live/index.m3u8",
  "source": "fadcam"
}
```

`DELETE /v1/creators/channels?id=creator-001` uses the same publisher authorization.

Creator endpoints require HTTPS streams. The registry is written using temporary-file replacement, file fsync and parent-directory fsync so a successful mutation is not reported as durable before the rename is persisted.

## Security properties

- HTTPS-only upstreams.
- No proxy environment variables for relay egress.
- DNS results resolving only to private/link-local/loopback addresses are rejected.
- HTTPS redirects are limited and cannot downgrade to HTTP.
- Playlist size is bounded.
- HLS child resources use expiring HMAC capabilities.
- Creator publishing is denied when no publisher key is configured.
- Corrupt creator registry data fails closed at startup.
- Catalog refresh failures retain the last known-good catalog.

## Android integration

Build the receiver with the catalog URL supplied at build time:

```text
./gradlew :tv-receiver:assembleRelease -PtvEastCatalogUrl=https://YOUR-TV-EAST-HOST
```

The resulting receiver is a separate standalone Android application with application ID `com.tv49east`.
