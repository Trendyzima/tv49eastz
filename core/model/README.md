# Canonical domain model

These models are the platform-owned vocabulary for channels, streams, providers, EPG, devices and playback.

## Flow

```text
Playlist adapter
      |
      v
   Channel
      |
      v
Stream -> StreamSource
      |
      v
stream-gateway
      |
      v
StreamSession -> PlaybackState
```

## Rules

1. Adapters translate external/provider data into these models.
2. UI code must not depend directly on imported provider implementations.
3. Gateway/tunnel code must not depend on provider-specific playlist or EPG types.
4. Secrets, private keys and bearer tokens are intentionally excluded from the domain models.
5. IDs are stable strings owned by the platform; provider-native IDs belong in adapter mappings.
6. Timestamps are serialized as ISO-8601 UTC strings at the boundary.

The FadCam server remains outside this domain model and is accessed through the read-only server-tap boundary.
