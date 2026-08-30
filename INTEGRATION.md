# Unified TV platform integration

This repository keeps the existing Android application as the host project and integrates three independently maintained source components as pinned Git submodules:

- `components/player` — Android TV/IPTV player source
- `components/channel-engine` — live-channel scheduling and EPG/server source
- `components/media-router` — live media routing, proxying, recording and playback server source

The submodules are pinned to known commits when the superproject is updated. They are kept in neutral component paths so the host repository has one coherent project layout without pretending the upstream projects have identical build systems or licenses.

## Integration rule

Do not copy or rename upstream source blindly. Integration work belongs at the component boundaries: APIs, stream URLs, playlists/EPG, authentication, health/observability, and deployment configuration. Each component retains its upstream license and attribution.
