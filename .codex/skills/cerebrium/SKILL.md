---
name: cerebrium
description: >-
  Use for TV 49 East Cerebrium work: deploying Python inference/media workloads to serverless CPU or GPU,
  writing and validating cerebrium.toml, selecting hardware/regions, autoscaling, REST/SSE/WebSocket/async
  endpoints, secrets, CI/CD and debugging builds, queueing and 5xx failures.
license: MIT
metadata:
  author: TV 49 East / Cerebrium workflow
---

# Cerebrium integration skill

Cerebrium is the optional heavy-compute plane for TV 49 East. Keep latency-sensitive Android UI, Supabase
metadata/auth, Cloudflare edge routing and R2 delivery separate from GPU/CPU inference jobs.

## TV 49 East workloads

Use Cerebrium for workloads that benefit from burstable CPU/GPU compute:

- social-content moderation and classification
- embeddings and semantic search/index refreshes
- image/video understanding and metadata extraction
- translation and language processing
- recommendation/ranking experiments
- future real-time voice/video AI

Do not put Supabase service-role keys, PayPal secrets, Cloudflare API tokens or Android signing keys in source.
Use Cerebrium secrets/environment variables instead.

## Deployment rules

1. A Cerebrium deploy starts billable compute. Never deploy a new app automatically without an explicit user
   confirmation for the first billable deployment in a session.
2. `cerebrium run` executes remotely; it is not a local emulator.
3. Keep every non-default setting explicitly in `cerebrium.toml`.
4. Never invent GPU identifiers or configuration keys; validate against Cerebrium's current references.
5. Cortex is preferred for ordinary Python functions and SSE. Use a custom runtime for ASGI/FastAPI or
   bidirectional WebSockets.
6. Keep `min_replicas = 0` for bursty inference where cold-start latency is acceptable; raise it only when
   continuous availability is worth the compute cost.
7. Keep `max_replicas` bounded so a traffic spike cannot create an uncontrolled bill.

## CLI workflow

```bash
cerebrium version
cerebrium projects current
cerebrium run main.py::run
cerebrium deploy
cerebrium logs APP_NAME
```

For CI/headless use, authenticate with `CEREBRIUM_SERVICE_ACCOUNT_TOKEN` or the CLI service-account option.

## TV 49 East integration contract

The Android app and Cloudflare gateway should call a stable application endpoint, never a Cerebrium internal
implementation detail. Suggested gateway route:

`POST /v1/ai/{task}`

The gateway authenticates the caller, validates task/input size, forwards only the required payload to
Cerebrium, applies timeout/retry policy, and strips provider-specific errors before returning a stable JSON
contract to Android.

Suggested tasks:

- `moderate_post`
- `embed_text`
- `rank_feed`
- `analyze_media`
- `translate_post`

For asynchronous work, persist a job record in Supabase and return a job id. Do not block a social post write
on a long-running GPU job.

## Endpoint modes

- REST: normal request/response inference.
- SSE: token/event streaming where the Python function yields events.
- WebSocket: only when a custom runtime owns the bidirectional server.
- Async: use for long media jobs, embeddings/indexing and batch processing.

## Health and observability

Every deployment must expose a cheap health path/function and log a correlation id. Monitor queue time,
replica count, execution time, error rate and cold-start time. A sustained queue with healthy workers is a
scaling/concurrency problem; repeated 5xx is an application/provider problem and should be debugged before
raising capacity.

## Security

Treat all federated/user media as untrusted. Validate MIME, size, URL schemes and remote domains before AI
processing. Never allow a federation URL to become an unrestricted fetch primitive or SSRF proxy.
