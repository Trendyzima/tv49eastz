package main

import "testing"

func TestDecideCaptureQualityPrefersStableHighestVideoAnd48KAudio(t *testing.T) {
	decision := DecideCaptureQuality([]VideoCapability{
		{Width: 1920, Height: 1080, MaxFPS: 60, MaxBitrate: 24000000, HEVC: true, Stable: true},
		{Width: 3840, Height: 2160, MaxFPS: 30, MaxBitrate: 50000000, HEVC: true, Stable: true},
		{Width: 4096, Height: 2160, MaxFPS: 30, Stable: false},
	}, AudioCapability{SampleRates: []int{44100, 48000}, Channels: 2}, CapturePresetCreator, true, "auto")
	if decision.Video.Width != 3840 || decision.Video.Height != 2160 || decision.Video.FPS != 30 {
		t.Fatalf("unexpected video profile: %+v", decision.Video)
	}
	if decision.Audio.SampleRate != 48000 || decision.Audio.Channels != 1 {
		t.Fatalf("unexpected audio profile: %+v", decision.Audio)
	}
}

func TestDecideCaptureQualityFallsBackToAVCAndSupportedAudioRate(t *testing.T) {
	decision := DecideCaptureQuality([]VideoCapability{{Width: 1280, Height: 720, MaxFPS: 30, Stable: true}}, AudioCapability{SampleRates: []int{44100}, Channels: 2}, CapturePresetBalanced, false, "external")
	if decision.Video.Codec != "avc" {
		t.Fatalf("expected avc, got %s", decision.Video.Codec)
	}
	if decision.Audio.SampleRate != 44100 {
		t.Fatalf("expected 44100, got %d", decision.Audio.SampleRate)
	}
	if len(decision.Warnings) == 0 {
		t.Fatal("expected audio fallback warning")
	}
}

func TestLowLightCapsFrameRateTo24FPS(t *testing.T) {
	decision := DecideCaptureQuality([]VideoCapability{{Width: 3840, Height: 2160, MaxFPS: 60, Stable: true}}, AudioCapability{SampleRates: []int{48000}, Channels: 2}, CapturePresetLowLight, false, "builtin")
	if decision.Video.FPS != 24 {
		t.Fatalf("expected low-light 24 FPS cap, got %d", decision.Video.FPS)
	}
}

func TestNoStableVideoUsesExplicitSafeFallbackWarning(t *testing.T) {
	decision := DecideCaptureQuality([]VideoCapability{{Width: 4096, Height: 2160, MaxFPS: 60, Stable: false}}, AudioCapability{SampleRates: []int{48000}, Channels: 1}, CapturePresetMaximum, false, "builtin")
	if decision.Video.Width != 1280 || decision.Video.Height != 720 || decision.Video.Codec != "avc" {
		t.Fatalf("unexpected safe fallback: %+v", decision.Video)
	}
	if len(decision.Warnings) != 1 || decision.Warnings[0] != "no stable advertised video profile; using safe 720p AVC fallback" {
		t.Fatalf("unexpected warnings: %#v", decision.Warnings)
	}
}
