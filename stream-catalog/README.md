# TV 49 East channel catalog

This service supplies the receiver with a refreshed catalog of publicly listed IPTV channels.

## Source

By default the catalog reads the main playlist published by `iptv-org/iptv`:

`https://iptv-org.github.io/iptv/index.m3u`

The upstream project describes its repository as a collection of publicly available IPTV channel links and states that it does not store video files. Channel availability and the rights to redistribute or relay an individual stream remain properties of the stream owner, not of this catalog service.

## Endpoints

- `GET /health` — service health and channel count.
- `GET /v1/catalog` — JSON channel catalog.
- `GET /v1/catalog?country=KE` — optional country filter.
- `GET /v1/catalog?group=News` — optional group filter.

Each imported channel is marked `relay: false`. TV 49 East should normally play these HTTPS sources directly. A relay should only be enabled separately for a source that TV 49 East is authorized to redistribute; the public playlist is a discovery source, not a blanket relay authorization.

## Configuration

- `IPTV_ORG_PLAYLIST` — HTTPS playlist URL; defaults to the main iptv-org playlist.
- `CATALOG_REFRESH_MINUTES` — refresh interval; default 30.
- `CATALOG_TIMEOUT_SECONDS` — upstream request timeout; default 20.
- `CATALOG_MAX_BYTES` — playlist size limit; default 32 MiB.
- `CATALOG_LISTEN` — bind address; default `:8790`.

The catalog retains the last known-good snapshot when an upstream refresh fails, preventing a transient upstream outage from emptying the receiver's channel list.
