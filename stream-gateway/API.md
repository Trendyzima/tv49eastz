# Public streaming API

The gateway presents stable public routes while keeping the existing server private.

## Routes

`GET /stream/{id}/index.m3u8`

Returns a rewritten HLS manifest for an authorized viewer.

`GET /stream/{id}/init.mp4`

Returns the initialization segment from the upstream server.

`GET /stream/{id}/segment/{name}`

Returns an HLS media segment requested by the manifest.

`GET /health`

Returns gateway health only; it does not expose the upstream URL.

## Viewer flow

1. Authenticate.
2. Request the stable stream URL.
3. Gateway resolves the stream to the tap.
4. Tap reads the existing server.
5. Gateway returns a client-safe manifest.
6. Subsequent HLS resources are proxied through the same boundary.

The client never needs to know the device's private LAN address.
