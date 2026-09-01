# FadCam publisher-control boundary

This boundary is deliberately separate from the media path.

```text
FadCam Android
  │  signed request (Android Keystore EC key)
  ▼
127.0.0.1:8789 publisher-control
  │  verifies device ID + pinned public key + nonce + timestamp + signature
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

## Security boundary

The Android APK must **not** contain `GATEWAY_API_KEY`, the gateway client private key, or any other master gateway credential. The agent keeps those credentials outside the APK.

FadCam creates an Android Keystore P-256 signing key. Each Start-TV request signs:

```text
1|nonce|iat|device_id|channel_id|stream_id
```

The agent rejects an unknown device, a different public key, an invalid ECDSA signature, stale/future requests, replayed nonces, or malformed/oversized JSON. Session responses are also constrained to HTTPS `/stream/...` playlists and at most 15 minutes.

The agent then calls the existing `POST /v1/session` gateway contract with its server-side API key and mTLS client certificate. The gateway performs the normal channel/stream authorization and tunnel-availability checks.

## One-time enrollment

The previous design required the agent to be preloaded with a public-key file. That cannot work with a newly generated Android Keystore key unless the two are explicitly paired. The repaired boundary uses a one-time enrollment token instead.

1. Configure `PUBLISHER_PUBLIC_KEY_FILE` and a high-entropy `PUBLISHER_ENROLL_TOKEN` on the agent. The key file may be absent on first boot.
2. Start the agent. It will fail closed unless the missing key is accompanied by the enrollment token.
3. From the FadCam enrollment flow, call `FadCamTvPublisher.provision(deviceId, enrollmentToken)` using the exact `TUNNEL_DEVICE_ID` registered for that tunnel.
4. FadCam sends its Keystore public key to loopback. The agent verifies the token and device ID, atomically writes the exact DER public key to `PUBLISHER_PUBLIC_KEY_FILE`, pins it in memory, and clears the token from the running process.
5. FadCam stores only the provisioned device ID. The private key never leaves Android Keystore and the enrollment token is never persisted by the APK.
6. After successful enrollment, remove/rotate the provisioning token from the agent's startup environment. A different public key can never replace an already-pinned identity through this endpoint.

`GET /v1/publisher/identity` exposes only the device ID, provisioning state, and public-key fingerprint on the loopback interface; it does not expose private material.

## Co-location requirement

The publisher-control listener is deliberately loopback-only. Therefore the FadCam Android process and the device-tunnel agent must actually share the same device/network namespace. Running the agent on a remote VPS or another LAN host does **not** make `127.0.0.1:8789` reachable from the phone.

The media path remains:

```text
FadCam Server Room :8080
        ↓
server-tap :8788
        ↓
device-tunnel (mTLS)
        ↓
stream-gateway
        ↓
TV 49 East
```

The existing FadCam Server Room is not modified by publisher control.

## Lifecycle

`startTvAndLaunch(...)` performs:

1. explicit Start-TV invocation;
2. signed publisher request;
3. gateway session creation;
4. HTTPS playlist validation;
5. signed `fadcam://stream` construction;
6. explicit launch of `com.tv49.com`.

`stopTv(...)` calls the agent revoke endpoint, which immediately calls gateway `DELETE /v1/session/{id}`. IPTV playback remains independent of this FadCam producer path.
