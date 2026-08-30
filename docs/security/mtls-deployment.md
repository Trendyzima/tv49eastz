# mTLS device-to-gateway deployment

This document defines the production boundary for the protected FadCam source. It does **not** modify FadCam.

## Trust model

```text
TV49Eastz private CA
├── gateway certificate (serverAuth)
└── device certificate (clientAuth)
        |
        v
   mTLS tunnel
        |
        v
 stream-gateway
        |
        v
 server-tap -> FadCam :8080
```

### Identity rules

- Create a private CA in deployment infrastructure, never in Git.
- Issue one certificate per gateway and per streaming device.
- Use `clientAuth` EKU for device certificates and `serverAuth` EKU for gateway certificates.
- Give each device a stable certificate identity (SAN/URI or SPIFFE-style ID) and map that identity to the platform `Device`/`Principal` record.
- Rotate certificates before expiry and revoke compromised identities.
- Private keys stay on the device/gateway secret store with restrictive permissions.

## Tunnel contract

The tunnel exposes the server-tap listener to the gateway through an authenticated, encrypted connection. The gateway must connect to the tunnel address, never directly to the device LAN address.

```text
DEVICE
FadCam :8080 (unchanged)
   |
server-tap :8788 (private/loopback)
   |
 mTLS tunnel client
   |
================ INTERNET ================
   |
 mTLS tunnel listener
   |
stream-gateway
```

The tunnel MUST NOT expose FadCam :8080 as a public listener. Only the server-tap HTTP surface is transported.

## Secret requirements

Required deployment secrets/configuration:

- private CA certificate
- gateway certificate + private key
- device certificate + private key
- tunnel endpoint and device identity
- server-tap upstream address on the device

Never commit any private key, issued certificate bundle containing private material, bearer credential, or live endpoint secret to this repository.

## Verification sequence

1. Verify gateway certificate chain and SAN.
2. Verify device certificate chain and `clientAuth` EKU.
3. Verify the gateway rejects a client without a trusted certificate.
4. Verify a valid device certificate maps to exactly one platform device identity.
5. Verify the tunnel can reach only server-tap.
6. Verify server-tap can reach only its configured FadCam origin.
7. Fetch `GET /live.m3u8` through the gateway.
8. Resolve `init.mp4` through the rewritten gateway URL.
9. Resolve at least one media segment.
10. Confirm the client never receives `192.168.100.5:8080`.
11. Kill/reconnect the tunnel and confirm sessions fail closed and recover without exposing the origin.

## Important

This file specifies the security contract only. Provisioning must happen in the deployment environment (for example a secret manager/PKI), not through GitHub source files.
