# FadCam Pro Capture Engine

## Objective
Provide the highest stable video and voice quality supported by the device while preserving synchronization, compatibility, thermal stability, and valid MP4 output.

## Quality principles

1. Capability-driven selection; never assume 4K/60 or HEVC is supported.
2. Prefer 48 kHz audio when supported.
3. Prefer clean microphone routing and expose the selected route.
4. Detect clipping, silence, excessive noise, and route loss.
5. Use bounded bitrate profiles based on resolution and frame rate.
6. Fall back deterministically when a codec, FPS, resolution, or audio mode is unavailable.
7. Preserve monotonic timestamps and valid finalized containers.
8. Validate recordings on physical devices with ffmpeg, ffprobe, and MP4 fragment checks.

## Presets

- Maximum Quality: highest stable resolution/FPS and high bitrate.
- Creator/Podcast: stable 1080p/4K at 30 FPS with voice-first audio.
- Balanced: high quality with moderate storage and thermal load.
- Low Light: prioritize exposure stability and lower FPS.
- Voice Priority: external microphone preference, clean audio, monitoring, stable video.

## Video selection order

4K60 -> 4K30 -> 1080p60 -> 1080p30 -> 720p30, subject to camera and encoder capabilities.

## Audio selection order

Explicit external route -> wired headset -> Bluetooth -> camcorder/primary microphone -> default microphone.

Preferred audio target: 48 kHz, AAC-compatible encoding, controlled gain, and no aggressive processing unless the device exposes a supported effect.

## Verification gates

- Unit-test profile ranking and fallback.
- Verify selected parameters against Camera2/CameraX and MediaCodec capabilities.
- Verify audio route and signal before capture.
- Verify no clipping/silence warnings during capture.
- Verify MP4 decodes cleanly with ffmpeg.
- Verify duration and stream metadata with ffprobe.
- Verify fragmented MP4 sample sizes and timestamps where applicable.
