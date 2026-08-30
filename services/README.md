# Platform services

Services orchestrate canonical domain models through `integration/contracts`.

```text
PlaylistProvider -> PlaylistService -> Playlist/Channel
EpgProvider      -> EpgService      -> EpgChannel/Program
ChannelProvider  -> ChannelService  -> Channel
StreamProvider   -> StreamService   -> Stream/StreamSource
DeviceProvider   -> DeviceService   -> Device
```

Services do not call the FadCam server directly. Protected streaming remains:

```text
FadCam server -> server-tap -> streaming adapter -> StreamSource -> StreamService
```

No service owns provider-specific DTOs, private tunnel credentials, or upstream addresses.
