package main

// StudioCapability identifies a production feature exposed by the Live Studio
// control plane. A capability is listed only when the current runtime can
// execute that capability; roadmap items remain absent until their renderer,
// media, or transport implementation is actually wired.
type StudioCapability string

const (
	CapabilitySceneManager       StudioCapability = "scene_manager"
	CapabilityPreviewProgram     StudioCapability = "preview_program"
	CapabilityCameraSource       StudioCapability = "camera_source"
	CapabilityVideoSource        StudioCapability = "video_source"
	CapabilityCompositor         StudioCapability = "compositor"
	CapabilityPIP                StudioCapability = "pip"
	CapabilityResolutionSelector StudioCapability = "resolution_selector"
	CapabilityProgramRecording   StudioCapability = "program_recording"
	CapabilityAudioMixer         StudioCapability = "audio_mixer"
	CapabilityVideoImport        StudioCapability = "video_import"
	CapabilityPlaybackControls   StudioCapability = "playback_controls"
	CapabilityGraphics           StudioCapability = "graphics"
	CapabilityLowerThirds        StudioCapability = "lower_thirds"
	CapabilityTicker             StudioCapability = "ticker"
	CapabilityLiveBadge          StudioCapability = "live_badge"
	CapabilityGraphicTemplates   StudioCapability = "graphic_templates"
	CapabilityTransitions        StudioCapability = "transitions"
	CapabilityRTMP               StudioCapability = "rtmp"
	CapabilityRTMPS              StudioCapability = "rtmps"
	CapabilitySRT                StudioCapability = "srt"
	CapabilityNetworkMonitoring  StudioCapability = "network_monitoring"
	CapabilityReconnect          StudioCapability = "reconnect"
	CapabilityLocalBackup        StudioCapability = "local_backup"
	CapabilityStreamHealth       StudioCapability = "stream_health"
	CapabilityMultiCamera        StudioCapability = "multi_camera"
	CapabilityRemoteGuests       StudioCapability = "remote_guests"
	CapabilityTeleprompter       StudioCapability = "teleprompter"
	CapabilityRemoteControl      StudioCapability = "remote_control"
	CapabilityAICaptions         StudioCapability = "ai_captions"
	CapabilityMultiDestination   StudioCapability = "multi_destination"
	CapabilityISORecording       StudioCapability = "iso_recording"
)

// StudioFeatureSet is returned to clients so the UI can enable controls only
// when their backend/device path is genuinely available. It is intentionally
// conservative: declaring a roadmap item as available would create a broken
// control surface and falsely imply that media rendering or transport exists.
type StudioFeatureSet struct {
	Phase1 []StudioCapability `json:"phase1"`
	Phase2 []StudioCapability `json:"phase2"`
	Phase3 []StudioCapability `json:"phase3"`
	Phase4 []StudioCapability `json:"phase4"`
	Phase5 []StudioCapability `json:"phase5"`
}

func DefaultStudioFeatureSet() StudioFeatureSet {
	return StudioFeatureSet{
		// Implemented today: authenticated session ownership, scene state,
		// preview/program selection, source registration/update, and audio state.
		Phase1: []StudioCapability{
			CapabilitySceneManager,
			CapabilityPreviewProgram,
			CapabilityCameraSource,
			CapabilityVideoSource,
			CapabilityAudioMixer,
		},
		// These remain disabled until their executable runtime is merged.
		Phase2: []StudioCapability{},
		Phase3: []StudioCapability{},
		Phase4: []StudioCapability{},
		Phase5: []StudioCapability{},
	}
}
