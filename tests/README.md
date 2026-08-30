# Vertical-slice validation

The first remote HLS smoke test is `tests/e2e/hls_remote_test.sh`.

It intentionally accepts only a public HTTPS gateway URL and rejects loopback/private FadCam addresses. It does not contact or expose the protected FadCam endpoint directly.

Production validation must run with a provisioned gateway and device tunnel:

1. mTLS device tunnel connects.
2. Gateway authenticates the device.
3. Authorized user receives an opaque `/stream/<session>/index.m3u8` URL.
4. Manifest is fetched through the gateway.
5. Initialization segment is fetched through the gateway.
6. Media segments are fetched through the gateway.
7. Playback remains healthy across reconnect/failure tests.
8. 1h/6h/24h soak tests are run against the deployed environment.

No repository commit can substitute for the live-network steps above.
