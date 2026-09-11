# FadCam Live Studio implementation matrix

The studio is organized around one session, many sources, scenes, one program output, and optional local/live sinks.

## Phase 1: Core engine
- Session manager and authenticated ownership
- Scene CRUD and preview/program state
- Camera and video source abstractions
- GPU compositor contract
- Normalized, movable/resizable PiP geometry
- Capability-safe resolution selection
- Program recording output
- Per-source audio mixer state

## Phase 2: Producer commentary
- Device media import and URI validation
- Camera plus video PiP scene
- Position and size controls
- Play/pause/seek/restart/loop/mute
- Persisted scenes
- Program-output MP4 verification

## Phase 3: Broadcast graphics
- Logos and watermarks
- Lower thirds and text overlays
- Tickers and live badge
- Reusable graphic templates
- Transition definitions with bounded duration

## Phase 4: Live streaming
- RTMP, RTMPS and SRT configuration
- Network telemetry and dropped-frame metrics
- Exponential reconnect policy
- Local recording backup while streaming
- Stream health state for the producer UI

## Phase 5: Advanced studio
- Multi-camera sources
- Remote guests
- Teleprompter
- Remote producer control
- AI caption capability flag
- Multi-destination output
- ISO recording capability flag

The capability set is intentionally declarative. A capability must not be shown as executable merely because it exists in the roadmap; the device/runtime adapter must advertise it as available.