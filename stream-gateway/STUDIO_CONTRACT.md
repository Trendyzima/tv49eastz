# FadCam Live Studio Contract

This contract defines the first safe control-plane milestone for Live Camera Studio. It lives outside the protected Android application boundary and is transport-neutral so the Android producer, gateway, and future browser control deck can share the same schema.

## Studio primitives

- **Sources:** camera, imported video, remote guest, image.
- **Scenes:** fullscreen, picture-in-picture, horizontal split, vertical split, grid.
- **Normalized canvas:** source rectangles use `x`, `y`, `w`, `h` in the inclusive `0..1` canvas. A PiP at `{x:0.68,y:0.05,w:0.28,h:0.28}` is top-right and remains portable across resolutions.
- **Overlays:** lower thirds, tickers, logos/text, visibility and opacity.
- **Audio buses:** per-source volume, mute, noise suppression and bounded synchronization delay.
- **Outputs:** local recording, live streaming, or both.

## Output policy

The contract validates dimensions, frame rate, bitrates, source references, scene IDs, normalized geometry and live protocols (`rtmp`, `rtmps`, `srt`). It rejects invalid or ambiguous sessions before they reach a renderer or streaming transport.

## Next integration gates

1. Android producer serializes a validated `StudioSessionSpec`.
2. The compositor consumes the active scene and produces one program frame.
3. Local recording and live streaming consume the same program output.
4. Scene switching changes only the active scene; it must not create competing encoders.
5. The gateway authenticates the control request and never exposes private device URLs.
