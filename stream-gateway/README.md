# Stream Gateway

The gateway is the public-facing half of the read-only streaming bridge. It talks only to the configured `server-tap`; it never accepts an arbitrary upstream URL and never exposes the upstream address in HLS responses.

## Flow

`FadCam :8080` → `server-tap` → `stream-gateway` → authorized HLS client

The FadCam server is not modified by this service.

## Start

```sh
GATEWAY_API_KEY='replace-with-a-long-random-secret' \
TAP_UPSTREAM='http://127.0.0.1:8786' \
go run .
```

Create a short-lived stream session:

```sh
curl -H 'Authorization: Bearer replace-with-a-long-random-secret' \
  http://127.0.0.1:8787/v1/session
```

The response contains an opaque `/stream/<session>/index.m3u8` URL. HLS media requests use that opaque session and therefore do not require the API key header.

## Security properties

- API key is required to mint a session.
- Sessions expire after 15 minutes.
- Active sessions are capped by `MAX_SESSIONS`.
- Requests are rate-limited per source address.
- Only GET is allowed on stream resources.
- The gateway follows no upstream redirects.
- HLS resource references must be relative and are rewritten to opaque gateway URLs.
- Absolute/external URLs and traversal paths are rejected.
- Upstream addresses are never copied into the client-facing manifest.
- Response sizes and upstream timeouts are bounded.
- `/health` is intentionally unauthenticated for infrastructure probes; `/metrics` requires the API key.

This is an integration gateway, not an Internet-facing deployment recipe. Put it behind TLS and an appropriate edge firewall/reverse proxy before public exposure.
