# FadCam publisher-control boundary

This boundary is deliberately separate from the media path.

```text
FadCam
  │  signed request (Android Keystore EC key)
  ▼
127.0.0.1:8789 publisher-control
  │  verifies device ID + public key + nonce + timestamp + signature
  │  holds GATEWAY_API_KEY and device mTLS certificate
  ▼
stream-gateway POST /v1/session
  │
  ▼
15-minute HTTPS capability playlist
  │
  ▼
FadCam signed fadcam://stream handoff
  │
  ▼
TV receiver verifier → MainActivity → Media3
```

## Why this boundary exists

The Android APK must **not** contain `GATEWAY_API_KEY`, the gateway client private key, or any other master gateway credential. The agent is the trusted device-side control component and keeps those credentials outside the APK.

The FadCam APK creates an Android Keystore P-256 signing key. Its public key is enrolled into `PUBLISHER_PUBLIC_KEY_FILE`. Each Start-TV request signs:

```text
1|nonce|iat|device_id|channel_id|stream_id
```

The agent rejects:

- an unknown device ID;
- a different public key;
- an invalid ECDSA signature;
- requests older than 30 seconds or too far in the future;
- a replayed nonce;
- malformed/oversized JSON.

The agent then calls the existing `POST /v1/session` gateway contract with its server-side API key and mTLS client certificate. The gateway still performs its normal channel/stream authorization and tunnel-availability checks.

## Enrollment

1. Start FadCam once and call `FadCamTvPublisher.getPublisherPublicKeyBase64(...)` from the device enrollment flow.
2. Decode that URL-safe base64 value to the DER SubjectPublicKeyInfo bytes.
3. Store those bytes as the file configured by `PUBLISHER_PUBLIC_KEY_FILE`.
4. Set `TUNNEL_DEVICE_ID` to the same enrolled device identity used by the gateway registry.
5. Configure `GATEWAY_CONTROL_URL`, `GATEWAY_API_KEY`, and the device mTLS files on the agent only.
6. Run the agent with `PUBLISHER_CONTROL_LISTEN=127.0.0.1:8789`.

The control listener is loopback-only. Possession of the port is not sufficient: a caller must also possess the enrolled Android Keystore private key.

## Lifecycle

`startTvAndLaunch(...)` performs:

1. signed publisher request;
2. gateway session creation;
3. short-lived HTTPS playlist validation;
4. signed `fadcam://stream` construction;
5. explicit launch of `com.tv49.com`.

`stopTv(...)` calls the agent's revoke endpoint, which immediately calls the gateway `DELETE /v1/session/{id}`. The existing FadCam Server Room is not modified by this control boundary.
