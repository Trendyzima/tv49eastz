package main

import (
	"errors"
	"fmt"
	"strings"
)

// StudioSourceKind identifies a source that can participate in the program output.
type StudioSourceKind string

const (
	SourceCamera StudioSourceKind = "camera"
	SourceVideo  StudioSourceKind = "video"
	SourceGuest  StudioSourceKind = "guest"
	SourceImage  StudioSourceKind = "image"
)

// StudioLayout describes the compositing arrangement for a scene.
type StudioLayout string

const (
	LayoutFullscreen StudioLayout = "fullscreen"
	LayoutPictureInPicture StudioLayout = "picture_in_picture"
	LayoutSplitHorizontal StudioLayout = "split_horizontal"
	LayoutSplitVertical StudioLayout = "split_vertical"
	LayoutGrid StudioLayout = "grid"
)

// StudioOutputConfig is deliberately transport-neutral. The Android producer,
// gateway, and future web control surface can share this contract without
// exposing application/server internals.
type StudioOutputConfig struct {
	Width       int    `json:"width"`
	Height      int    `json:"height"`
	FPS         int    `json:"fps"`
	VideoBitrate int   `json:"video_bitrate"`
	AudioBitrate int   `json:"audio_bitrate"`
	RecordLocal bool   `json:"record_local"`
	StreamLive  bool   `json:"stream_live"`
	Protocol    string `json:"protocol"`
}

type StudioRect struct {
	X float64 `json:"x"`
	Y float64 `json:"y"`
	W float64 `json:"w"`
	H float64 `json:"h"`
}

type StudioSource struct {
	ID       string           `json:"id"`
	Kind     StudioSourceKind `json:"kind"`
	Name     string           `json:"name"`
	URI      string           `json:"uri,omitempty"`
	Muted    bool             `json:"muted"`
	Loop     bool             `json:"loop"`
	Position StudioRect       `json:"position"`
	ZIndex   int              `json:"z_index"`
}

type StudioOverlay struct {
	ID         string     `json:"id"`
	Text       string     `json:"text"`
	Position   StudioRect `json:"position"`
	FontSize   int        `json:"font_size"`
	Opacity    float64    `json:"opacity"`
	Visible    bool       `json:"visible"`
	LowerThird bool       `json:"lower_third"`
	Ticker     bool       `json:"ticker"`
}

type StudioAudioBus struct {
	SourceID       string  `json:"source_id"`
	Volume         float64 `json:"volume"`
	Muted          bool    `json:"muted"`
	NoiseSuppression bool  `json:"noise_suppression"`
	DelayMS        int     `json:"delay_ms"`
}

type StudioScene struct {
	ID       string            `json:"id"`
	Name     string            `json:"name"`
	Layout   StudioLayout      `json:"layout"`
	Sources  []StudioSource    `json:"sources"`
	Overlays []StudioOverlay   `json:"overlays"`
	Audio    []StudioAudioBus  `json:"audio"`
}

type StudioSessionSpec struct {
	SessionID string             `json:"session_id"`
	OwnerID   string             `json:"owner_id"`
	Scenes    []StudioScene      `json:"scenes"`
	ActiveScene string           `json:"active_scene"`
	Output    StudioOutputConfig `json:"output"`
}

func (s StudioSessionSpec) Validate() error {
	if strings.TrimSpace(s.SessionID) == "" {
		return errors.New("session_id is required")
	}
	if strings.TrimSpace(s.OwnerID) == "" {
		return errors.New("owner_id is required")
	}
	if len(s.Scenes) == 0 || len(s.Scenes) > 64 {
		return errors.New("scenes must contain between 1 and 64 entries")
	}
	seen := make(map[string]struct{}, len(s.Scenes))
	for _, scene := range s.Scenes {
		if err := scene.Validate(); err != nil {
			return fmt.Errorf("scene %q: %w", scene.ID, err)
		}
		if _, exists := seen[scene.ID]; exists {
			return fmt.Errorf("duplicate scene id %q", scene.ID)
		}
		seen[scene.ID] = struct{}{}
	}
	if _, ok := seen[s.ActiveScene]; !ok {
		return errors.New("active_scene must reference an existing scene")
	}
	return s.Output.Validate()
}

func (s StudioScene) Validate() error {
	if strings.TrimSpace(s.ID) == "" || strings.TrimSpace(s.Name) == "" {
		return errors.New("id and name are required")
	}
	switch s.Layout {
	case LayoutFullscreen, LayoutPictureInPicture, LayoutSplitHorizontal,
		LayoutSplitVertical, LayoutGrid:
	default:
		return fmt.Errorf("unsupported layout %q", s.Layout)
	}
	if len(s.Sources) == 0 || len(s.Sources) > 16 {
		return errors.New("sources must contain between 1 and 16 entries")
	}
	seen := make(map[string]struct{}, len(s.Sources))
	for _, source := range s.Sources {
		if err := source.Validate(); err != nil {
			return fmt.Errorf("source %q: %w", source.ID, err)
		}
		if _, exists := seen[source.ID]; exists {
			return fmt.Errorf("duplicate source id %q", source.ID)
		}
		seen[source.ID] = struct{}{}
	}
	for _, bus := range s.Audio {
		if _, ok := seen[bus.SourceID]; !ok {
			return fmt.Errorf("audio references unknown source %q", bus.SourceID)
		}
		if bus.Volume < 0 || bus.Volume > 2 {
			return fmt.Errorf("audio volume for %q must be between 0 and 2", bus.SourceID)
		}
		if bus.DelayMS < -2000 || bus.DelayMS > 2000 {
			return fmt.Errorf("audio delay for %q is outside safe range", bus.SourceID)
		}
	}
	return nil
}

func (s StudioSource) Validate() error {
	if strings.TrimSpace(s.ID) == "" || strings.TrimSpace(s.Name) == "" {
		return errors.New("id and name are required")
	}
	switch s.Kind {
	case SourceCamera:
	case SourceVideo, SourceImage, SourceGuest:
		if strings.TrimSpace(s.URI) == "" {
			return errors.New("uri is required for non-camera sources")
		}
	default:
		return fmt.Errorf("unsupported source kind %q", s.Kind)
	}
	if s.Position.W <= 0 || s.Position.H <= 0 || s.Position.X < 0 || s.Position.Y < 0 ||
		s.Position.X+s.Position.W > 1 || s.Position.Y+s.Position.H > 1 {
		return errors.New("position must be normalized within the 0..1 canvas")
	}
	return nil
}

func (o StudioOutputConfig) Validate() error {
	if o.Width < 320 || o.Width > 7680 || o.Height < 240 || o.Height > 4320 {
		return errors.New("output dimensions are outside supported bounds")
	}
	if o.FPS < 1 || o.FPS > 120 {
		return errors.New("fps must be between 1 and 120")
	}
	if o.VideoBitrate < 100_000 || o.VideoBitrate > 100_000_000 {
		return errors.New("video_bitrate is outside safe bounds")
	}
	if o.AudioBitrate < 16_000 || o.AudioBitrate > 512_000 {
		return errors.New("audio_bitrate is outside safe bounds")
	}
	if o.StreamLive {
		switch o.Protocol {
		case "rtmp", "rtmps", "srt":
		default:
			return fmt.Errorf("unsupported live protocol %q", o.Protocol)
		}
	}
	if !o.RecordLocal && !o.StreamLive {
		return errors.New("at least one output must be enabled")
	}
	return nil
}
