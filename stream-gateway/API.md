# Stream Gateway API

The gateway presents stable HLS routes while keeping the existing FadCam Server Room private. IPTV streams do not use this gateway.

## Session lifecycle

### `POST /v1/session`

Creates an authenticated, device-bound ephemeral viewing/publishing session.

Request JSON:

```json
{"channel_id":"camera","stream_id":"fadcam-device-stream"}
```

The caller must present the gateway bearer credential and a verified mTLS device identity. The device must be registered and authorized for the requested channel.

Response:

```json
{"session":"<id>","expires_in":900,"playlist":"/stream/<id>/index.m3u8"}
```

### `DELETE /v1/session/{id}`

Immediately revokes a session. The caller must own the session's user/device/fingerprint identity. Repeating the request for an already-revoked session is idempotent and returns `204`.

Revocation removes the session from the active set, releases its session slot, and makes every previously issued capability URL unusable because resource requests revalidate the live session before proxying.

## Viewer routes

`GET /stream/{id}/index.m3u8`

Returns a rewritten HLS manifest for an authorized viewer.

`GET /stream/{id}/resource/{capability}`

Returns an HLS resource through the authenticated device tunnel using a session-bound HMAC capability.

`GET /health`

Returns gateway health only; it does not expose the upstream URL.

## Security/lifecycle invariants

1. FadCam must explicitly start a TV broadcast; recording alone does not publish it.
2. The FadCam Server Room remains the source of the local HLS stream.
3. `server-tap` remains read-only; the gateway never controls the camera/server.
4. The phone's private LAN address is never exposed to TV viewers.
5. IPTV feeds remain direct IPTV feeds and never traverse the FadCam tunnel.
6. Stop-TV revocation is immediate; the 15-minute expiry is only the safety timeout.
7. Sessions are bound to verified device identity and cannot be revoked by another device.
