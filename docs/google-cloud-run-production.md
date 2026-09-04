# Google Cloud Run production deployment

This repository now uses **Google Cloud Run as the Go gateway runtime** and **Cloudflare Workers + Durable Objects as the edge relay/device-tunnel layer**. Cloudflare Containers are no longer part of the production path.

Cloud Run supports standard Docker containers and WebSockets, with request timeouts up to 60 minutes. This deployment enables session affinity because the current Go gateway keeps sessions/tunnel registrations in process memory; affinity is only best-effort, so the service is deliberately capped at one instance until the state layer is made durable. citeturn1search1turn3search0

## Required one-time Google setup

Create/select a Google Cloud project and enable billing if required by your Google Cloud account. Then enable:

- Cloud Run API
- Artifact Registry API
- Secret Manager API
- IAM Service Account Credentials API
- Security Token Service API

Create a Docker Artifact Registry repository, for example `tv49eastz`, in the same region as Cloud Run. Google recommends Artifact Registry for container images. citeturn4search2

Create two service accounts:

1. `tv49eastz-deployer` — used only by GitHub Actions through Workload Identity Federation.
2. `tv49eastz-runtime` — attached to the Cloud Run revision.

The runtime service account needs `roles/secretmanager.secretAccessor` on the three application secrets. Cloud Run can inject Secret Manager values as environment variables; Google recommends Secret Manager rather than ordinary environment variables for sensitive values. citeturn1search6

Grant the deployer the minimum roles required for the pipeline. At minimum it needs Cloud Run deployment permissions, Artifact Registry write access, and permission to act as the runtime service account. The exact role split can be tightened further after the first successful deployment. Cloud Run deployment requires Cloud Run deployment access, Service Account User on the service identity, and Artifact Registry access. citeturn0search2

## Workload Identity Federation

Create a global workload identity pool and an OIDC provider for GitHub Actions. Use a restrictive attribute condition. For this repository, the provider should restrict tokens to:

```text
assertion.repository=='Trendyzima/tv49eastz' && assertion.ref=='refs/heads/master'
```

Map at least:

```text
google.subject=assertion.sub
attribute.repository=assertion.repository
attribute.repository_owner=assertion.repository_owner
attribute.ref=assertion.ref
```

Grant the GitHub principal the `roles/iam.workloadIdentityUser` role on the deployer service account. Google specifically recommends attribute conditions for GitHub because GitHub's OIDC issuer is shared across tenants. citeturn4search0turn4search1turn4search10

The GitHub Actions workflow uses `google-github-actions/auth@v3` with `id-token: write`; no long-lived Google service-account JSON key is stored in GitHub. citeturn0search3turn0search11

## GitHub repository variables

Add these repository **Variables**:

```text
GCP_PROJECT_ID=<your-project-id>
GCP_REGION=<for example europe-west1>
GCP_ARTIFACT_REPOSITORY=tv49eastz
GCP_CLOUD_RUN_SERVICE=tv49eastz-stream-gateway
GCP_WIF_PROVIDER=projects/<project-number>/locations/global/workloadIdentityPools/<pool>/providers/<provider>
GCP_DEPLOYER_SERVICE_ACCOUNT=tv49eastz-deployer@<project-id>.iam.gserviceaccount.com
```

Add these repository **Secrets**:

```text
CLOUDFLARE_ACCOUNT_ID
CLOUDFLARE_API_TOKEN
TV49_GATEWAY_API_KEY
TV49_GATEWAY_CAPABILITY_KEY
TV49_RELAY_SIGNING_SECRET
TV49_RELAY_DEVICE_SECRET
```

Do not commit any of these values. Cloudflare recommends Wrangler secrets for Worker credentials, and Google recommends Secret Manager for Cloud Run secrets. citeturn5search1turn1search6

## Deployment flow

`.github/workflows/cloud-run-deploy.yml` performs this sequence:

1. Authenticate GitHub Actions to Google Cloud using Workload Identity Federation.
2. Verify required Google APIs and the Artifact Registry repository.
3. Synchronize the three Cloud Run secrets into Secret Manager.
4. Build `stream-gateway/Dockerfile` as a Linux container.
5. Push the immutable commit-tagged image to Artifact Registry.
6. Deploy Cloud Run with port `8080`, 60-minute request timeout, session affinity, `max-instances=1`, and Secret Manager-backed runtime credentials.
7. Capture the actual Cloud Run `status.url` (`https://...run.app`).
8. Set the Cloudflare Worker `GATEWAY_ORIGIN` to that exact Cloud Run URL.
9. Keep `/device/*`, `/tunnel`, and relay Durable Objects on Cloudflare while `/health`, `/v1/session*`, and `/stream/*` are proxied to Cloud Run.
10. Verify Cloud Run health, the `/v1/session` authentication boundary, HLS routing, Worker health, and Worker → Cloud Run routing.

Cloud Run's WebSocket support is compatible with this architecture, but long-lived streams remain subject to the configured request timeout and clients should reconnect when a timeout or instance restart occurs. citeturn1search1turn1search0

## Important state note

The current gateway stores sessions and its device tunnel registry in process memory. Cloud Run session affinity is therefore enabled, but Google explicitly describes affinity as best-effort rather than durable state. The initial production configuration uses `max-instances=1` to prevent normal horizontal scaling from splitting a session across instances. A future durable session/tunnel state layer can remove that constraint. citeturn7search0

## No Cloudflare Container dependency

The Worker no longer imports `@cloudflare/containers`, no longer declares a `StreamGatewayContainer` binding, and no longer needs the Cloudflare Container registry. The legacy Durable Object namespace is retired with a migration while the existing `RelayTunnel` Durable Object remains active.

Cloudflare's current Durable Object migration model requires the old class to be removed from code/bindings before applying a deletion migration; this repository follows that pattern. citeturn2search6
