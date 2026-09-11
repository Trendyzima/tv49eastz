package main

// StudioCapability identifies a production feature exposed by the Live Studio
// control plane. Runtime implementations can advertise only capabilities that
// are actually available on the current device/session.
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
// when their backend/device path is genuinely available. It prevents exposing
// buttons for functionality that has not reached an executable implementation.
type StudioFeatureSet struct {
	Phase1 []StudioCapability `json:"phase1"`
	Phase2 []StudioCapability `json:"phase2"`
	Phase3 []StudioCapability `json:"phase3"`
	Phase4 []StudioCapability `json:"phase4"`
	Phase5 []StudioCapability `json:"phase5"`
}

func DefaultStudioFeatureSet() StudioFeatureSet {
	return StudioFeatureSet{
		Phase1: []StudioCapability{CapabilitySceneManager, CapabilityPreviewProgram, CapabilityCameraSource, CapabilityVideoSource, CapabilityCompositor, CapabilityPIP, CapabilityResolutionSelector, CapabilityProgramRecording, CapabilityAudioMixer},
		Phase2: []StudioCapability{CapabilityVideoImport, CapabilityPIP, CapabilityPlaybackControls},
		Phase3: []StudioCapability{CapabilityGraphics, CapabilityLowerThirds, CapabilityTicker, CapabilityLiveBadge, CapabilityGraphicTemplates, CapabilityTransitions},
		Phase4: []StudioCapability{CapabilityRTMP, CapabilityRTMPS, CapabilitySRT, CapabilityNetworkMonitoring, CapabilityReconnect, CapabilityLocalBackup, CapabilityStreamHealth},
		Phase5: []StudioCapability{CapabilityMultiCamera, CapabilityRemoteGuests, CapabilityTeleprompter, CapabilityRemoteControl, CapabilityAICaptions, CapabilityMultiDestination, CapabilityISORecording},
	}
}
