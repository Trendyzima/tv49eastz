# Server Room Protection

This directory defines the protection boundary around the existing FadCam server implementation.

## Non-negotiable rule

The existing FadCam server is an upstream source of truth. The streaming bridge must consume it through `server-tap`; it must not be rewritten to add public authentication, remote control, Internet exposure, or gateway behavior.

### Allowed architecture

```text
existing server :8080
        |
        | GET only
        v
   server-tap
        |
        v
 stream-gateway
        |
        v
 authorized users
```

### Prohibited changes

- Do not add public Internet listeners to the existing server.
- Do not add gateway authentication to the existing server.
- Do not forward gateway control requests into the existing server.
- Do not accept arbitrary upstream URLs from users.
- Do not expose the private upstream address in public manifests.
- Do not add application-level transcoding to the existing server.

### Hardening lives outside the server

Security controls belong in `server-tap` and `stream-gateway`: authentication, authorization, rate limits, session limits, HLS validation, URL rewriting, metrics, logging, and upstream isolation.

This separation is intentional: the server remains a stable producer while the bridge layer evolves independently.
