# Adapters

Adapters isolate imported implementations and infrastructure from the platform domain.

Planned boundaries:

- `playlist/`: M3U and playlist-source implementations -> `Playlist`, `Channel`, `Stream`.
- `epg/`: XMLTV/EPG implementations -> `EpgChannel`, `Program`.
- `streaming/`: server-tap/tunnel implementations -> `StreamSource`, `Stream`.
- `playback/`: player implementations -> platform playback state.

An adapter may depend on infrastructure, but application code must depend on the contracts rather than concrete upstream implementations.
