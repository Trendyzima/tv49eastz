package main

import "sort"

type CapturePreset string

const (
	CapturePresetMaximum       CapturePreset = "maximum"
	CapturePresetCreator       CapturePreset = "creator"
	CapturePresetBalanced      CapturePreset = "balanced"
	CapturePresetLowLight      CapturePreset = "low_light"
	CapturePresetVoicePriority CapturePreset = "voice_priority"
)

type VideoCapability struct {
	Width      int
	Height     int
	MaxFPS     int
	MaxBitrate int
	HEVC       bool
	Stable     bool
}

type AudioCapability struct {
	SampleRates []int
	Channels    int
	External    bool
}

type VideoProfile struct {
	Width       int
	Height      int
	FPS         int
	Bitrate     int
	Codec       string
	KeyframeSec int
}

type AudioProfile struct {
	SampleRate       int
	Channels         int
	Bitrate          int
	Source            string
	NoiseSuppression  bool
	EchoCancellation  bool
}

type CaptureQualityDecision struct {
	Preset   CapturePreset
	Video    VideoProfile
	Audio    AudioProfile
	Warnings []string
}

func chooseVideoProfile(caps []VideoCapability, preset CapturePreset, hevcAllowed bool) VideoProfile {
	candidates := append([]VideoCapability(nil), caps...)
	sort.Slice(candidates, func(i, j int) bool {
		a, b := candidates[i], candidates[j]
		areaA, areaB := a.Width*a.Height, b.Width*b.Height
		if areaA != areaB {
			return areaA > areaB
		}
		return a.MaxFPS > b.MaxFPS
	})

	for _, c := range candidates {
		if !c.Stable || c.Width <= 0 || c.Height <= 0 {
			continue
		}
		fps := c.MaxFPS
		if preset != CapturePresetMaximum && fps > 30 {
			fps = 30
		}
		if preset == CapturePresetLowLight && fps > 24 {
			fps = 24
		}
		if fps < 24 {
			continue
		}
		codec := "avc"
		if hevcAllowed && c.HEVC {
			codec = "hevc"
		}
		bitrate := c.MaxBitrate
		if bitrate <= 0 {
			bitrate = defaultVideoBitrate(c.Width, c.Height, fps)
		}
		return VideoProfile{Width: c.Width, Height: c.Height, FPS: fps, Bitrate: bitrate, Codec: codec, KeyframeSec: 2}
	}

	return VideoProfile{Width: 1280, Height: 720, FPS: 30, Bitrate: 4_000_000, Codec: "avc", KeyframeSec: 2}
}

func defaultVideoBitrate(width, height, fps int) int {
	pixels := width * height
	base := 8_000_000
	if pixels >= 3840*2160 {
		base = 50_000_000
	} else if pixels >= 1920*1080 {
		base = 16_000_000
	}
	if fps >= 60 {
		base = base * 3 / 2
	}
	return base
}

func chooseAudioProfile(caps AudioCapability, preset CapturePreset, source string) AudioProfile {
	rate := 48000
	found := false
	for _, r := range caps.SampleRates {
		if r == 48000 {
			found = true
			break
		}
	}
	if !found && len(caps.SampleRates) > 0 {
		rate = caps.SampleRates[0]
	}
	channels := caps.Channels
	if channels < 1 {
		channels = 1
	}
	if channels > 2 {
		channels = 2
	}
	if preset == CapturePresetVoicePriority || preset == CapturePresetCreator {
		channels = 1
	}
	if source == "" {
		source = "auto"
	}
	return AudioProfile{
		SampleRate: rate, Channels: channels, Bitrate: 192000, Source: source,
		NoiseSuppression: true, EchoCancellation: true,
	}
}

func hasStableVideoCapability(caps []VideoCapability) bool {
	for _, c := range caps {
		if c.Stable && c.Width > 0 && c.Height > 0 && c.MaxFPS >= 24 {
			return true
		}
	}
	return false
}

func DecideCaptureQuality(caps []VideoCapability, audio AudioCapability, preset CapturePreset, hevcAllowed bool, source string) CaptureQualityDecision {
	video := chooseVideoProfile(caps, preset, hevcAllowed)
	audioProfile := chooseAudioProfile(audio, preset, source)
	warnings := []string{}
	if !hasStableVideoCapability(caps) {
		warnings = append(warnings, "no stable advertised video profile; using safe 720p AVC fallback")
	}
	if video.Width >= 3840 && video.FPS >= 60 {
		warnings = append(warnings, "high thermal and storage load")
	}
	if audioProfile.SampleRate != 48000 {
		warnings = append(warnings, "48 kHz audio unavailable; using device-supported rate")
	}
	return CaptureQualityDecision{Preset: preset, Video: video, Audio: audioProfile, Warnings: warnings}
}
