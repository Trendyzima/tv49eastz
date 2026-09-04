# TV 49 East Vercel Edge Federation Front Door

This directory adds a stateless Vercel Edge front door in front of the existing ActivityPub federation worker.

## Architecture

`Fediverse -> Vercel Edge -> Cloudflare Federation Worker -> Supabase`

Optional failover:

`Fediverse -> Vercel Edge -> FEDERATION_ORIGIN`
`                         -> FEDERATION_FALLBACK_ORIGIN`

The Edge layer handles:

- WebFinger and NodeInfo discovery forwarding.
- ActivityPub actor/object GET forwarding.
- Signed ActivityPub POST forwarding without modifying the signature/body.
- Edge health checks at `/api/federation/health`.
- Automatic failover when an origin returns 5xx/429 or is unreachable.
- Short cache headers for public ActivityPub GET responses.
- Request/response forwarding headers for observability.

## Required Vercel environment variables

Set these in the Vercel project; do not commit them:

- `FEDERATION_ORIGIN` — primary federation worker origin, for example `https://federation.testagram.site`.
- `FEDERATION_FALLBACK_ORIGIN` — optional second federation origin in another deployment/region/provider.

The Edge layer intentionally contains no Supabase service-role key and no federation private key.

## Important operational boundary

Vercel Edge is a stateless HTTP edge layer, not a VPS and not a persistent ActivityPub daemon. It improves global ingress, failover and latency, but durable federation state, delivery queues, retries and signing remain in the federation backend/queue layer.

The five-minute Vercel cron only probes `/api/federation/health`; it does not pretend to keep a worker process alive.

## Custom domain

Point the federation hostname at this Vercel project only after the Vercel deployment and environment variables are configured. The ActivityPub actor URLs must remain canonical and consistent with `ACTOR_BASE_URL`; do not silently change actor IDs during failover.
