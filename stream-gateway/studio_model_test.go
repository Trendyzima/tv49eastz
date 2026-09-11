package main

import "testing"

func validStudioSpec() StudioSessionSpec {
	return StudioSessionSpec{
		SessionID: "studio-1",
		OwnerID: "user-1",
		Scenes: []StudioScene{{
			ID: "commentary",
			Name: "Camera and clip",
			Layout: LayoutPictureInPicture,
			Sources: []StudioSource{
				{ID: "camera", Kind: SourceCamera, Name: "Presenter", Position: StudioRect{X: 0, Y: 0, W: 1, H: 1}},
				{ID: "clip", Kind: SourceVideo, Name: "Clip", URI: "content://clip", Position: StudioRect{X: .68, Y: .05, W: .28, H: .28}},
			},
			Audio: []StudioAudioBus{{SourceID: "camera", Volume: 1}},
		}},
		ActiveScene: "commentary",
		Output: StudioOutputConfig{Width: 1920, Height: 1080, FPS: 30, VideoBitrate: 6_000_000, AudioBitrate: 128_000, RecordLocal: true},
	}
}

func TestStudioSessionSpecValidate(t *testing.T) {
	if err := validStudioSpec().Validate(); err != nil {
		t.Fatalf("valid spec rejected: %v", err)
	}
}

func TestStudioRejectsUnknownActiveScene(t *testing.T) {
	s := validStudioSpec()
	s.ActiveScene = "missing"
	if err := s.Validate(); err == nil {
		t.Fatal("expected unknown active scene to be rejected")
	}
}

func TestStudioRejectsOutOfCanvasPiP(t *testing.T) {
	s := validStudioSpec()
	s.Scenes[0].Sources[1].Position = StudioRect{X: .8, Y: .8, W: .4, H: .4}
	if err := s.Validate(); err == nil {
		t.Fatal("expected out-of-canvas source to be rejected")
	}
}

func TestStudioRejectsUnsafeLiveProtocol(t *testing.T) {
	s := validStudioSpec()
	s.Output.RecordLocal = false
	s.Output.StreamLive = true
	s.Output.Protocol = "udp"
	if err := s.Validate(); err == nil {
		t.Fatal("expected unsupported protocol to be rejected")
	}
}

func TestStudioRejectsUnknownAudioSource(t *testing.T) {
	s := validStudioSpec()
	s.Scenes[0].Audio[0].SourceID = "not-present"
	if err := s.Validate(); err == nil {
		t.Fatal("expected unknown audio source to be rejected")
	}
}
