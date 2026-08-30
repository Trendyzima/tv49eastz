# Integration contracts

These interfaces are the platform boundary around imported/upstream implementations.

Rules:

1. Adapters return only `core.model` types.
2. Provider-specific DTOs, URLs, credentials, and implementation details stay inside adapters.
3. The FadCam server is not a domain object and is never called directly by application code.
4. Streaming enters the platform through a `StreamSource` produced by an adapter.
5. Authentication secrets and private tunnel credentials never belong in domain models or contracts.

Canonical flow:

`Provider -> Adapter -> canonical model -> service -> application/gateway`

For protected-device streaming:

`FadCam server -> server-tap -> streaming adapter -> StreamSource -> StreamSession -> playback`
