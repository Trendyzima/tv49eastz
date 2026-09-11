package main

import (
	"errors"
	"fmt"
	"sync"
	"time"
)

// StudioRegistry owns mutable studio state independently from transport sessions.
// Every mutation is serialized and every returned session is a defensive copy.
type StudioRegistry struct {
	mu       sync.RWMutex
	sessions map[string]*StudioRuntime
}

type StudioRuntime struct {
	Spec          StudioSessionSpec `json:"spec"`
	Sources       map[string]StudioSource `json:"sources"`
	ProgramScene  string            `json:"program_scene"`
	OutputRunning bool              `json:"output_running"`
	UpdatedAt     time.Time         `json:"updated_at"`
}

func NewStudioRegistry() *StudioRegistry {
	return &StudioRegistry{sessions: make(map[string]*StudioRuntime)}
}

func (r *StudioRegistry) Create(spec StudioSessionSpec) (StudioRuntime, error) {
	if err := spec.Validate(); err != nil {
		return StudioRuntime{}, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.sessions[spec.SessionID]; exists {
		return StudioRuntime{}, fmt.Errorf("studio session %q already exists", spec.SessionID)
	}
	rt := runtimeFromSpec(spec)
	r.sessions[spec.SessionID] = &rt
	return cloneRuntime(rt), nil
}

func (r *StudioRegistry) Get(id string) (StudioRuntime, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	rt, ok := r.sessions[id]
	if !ok {
		return StudioRuntime{}, false
	}
	return cloneRuntime(*rt), true
}

func (r *StudioRegistry) Delete(id string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.sessions[id]; !ok {
		return false
	}
	delete(r.sessions, id)
	return true
}

func (r *StudioRegistry) UpdateScene(sessionID string, scene StudioScene) (StudioRuntime, error) {
	if err := scene.Validate(); err != nil {
		return StudioRuntime{}, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	rt, ok := r.sessions[sessionID]
	if !ok {
		return StudioRuntime{}, errors.New("studio session not found")
	}
	for i := range rt.Spec.Scenes {
		if rt.Spec.Scenes[i].ID == scene.ID {
			rt.Spec.Scenes[i] = scene
			rt.UpdatedAt = time.Now().UTC()
			return cloneRuntime(*rt), nil
		}
	}
	rt.Spec.Scenes = append(rt.Spec.Scenes, scene)
	rt.UpdatedAt = time.Now().UTC()
	return cloneRuntime(*rt), nil
}

func (r *StudioRegistry) DeleteScene(sessionID, sceneID string) (StudioRuntime, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	rt, ok := r.sessions[sessionID]
	if !ok {
		return StudioRuntime{}, errors.New("studio session not found")
	}
	if rt.Spec.ActiveScene == sceneID {
		return StudioRuntime{}, errors.New("cannot delete active scene")
	}
	for i := range rt.Spec.Scenes {
		if rt.Spec.Scenes[i].ID == sceneID {
			rt.Spec.Scenes = append(rt.Spec.Scenes[:i], rt.Spec.Scenes[i+1:]...)
			rt.UpdatedAt = time.Now().UTC()
			return cloneRuntime(*rt), nil
		}
	}
	return StudioRuntime{}, errors.New("scene not found")
}

func (r *StudioRegistry) ActivateScene(sessionID, sceneID string) (StudioRuntime, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	rt, ok := r.sessions[sessionID]
	if !ok {
		return StudioRuntime{}, errors.New("studio session not found")
	}
	for _, scene := range rt.Spec.Scenes {
		if scene.ID == sceneID {
			rt.Spec.ActiveScene = sceneID
			rt.ProgramScene = sceneID
			rt.UpdatedAt = time.Now().UTC()
			return cloneRuntime(*rt), nil
		}
	}
	return StudioRuntime{}, errors.New("scene not found")
}

func (r *StudioRegistry) RegisterSource(sessionID string, source StudioSource) (StudioRuntime, error) {
	if err := source.Validate(); err != nil {
		return StudioRuntime{}, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	rt, ok := r.sessions[sessionID]
	if !ok {
		return StudioRuntime{}, errors.New("studio session not found")
	}
	if _, exists := rt.Sources[source.ID]; exists {
		return StudioRuntime{}, errors.New("source already registered")
	}
	rt.Sources[source.ID] = source
	rt.UpdatedAt = time.Now().UTC()
	return cloneRuntime(*rt), nil
}

func (r *StudioRegistry) SetOutput(sessionID string, output StudioOutputConfig, running bool) (StudioRuntime, error) {
	if err := output.Validate(); err != nil {
		return StudioRuntime{}, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	rt, ok := r.sessions[sessionID]
	if !ok {
		return StudioRuntime{}, errors.New("studio session not found")
	}
	rt.Spec.Output = output
	rt.OutputRunning = running
	rt.UpdatedAt = time.Now().UTC()
	return cloneRuntime(*rt), nil
}

func (r *StudioRegistry) SetAudio(sessionID string, audio []StudioAudioBus) (StudioRuntime, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	rt, ok := r.sessions[sessionID]
	if !ok {
		return StudioRuntime{}, errors.New("studio session not found")
	}
	active, ok := findScene(rt.Spec.Scenes, rt.Spec.ActiveScene)
	if !ok {
		return StudioRuntime{}, errors.New("active scene not found")
	}
	candidate := active
	candidate.Audio = append([]StudioAudioBus(nil), audio...)
	if err := candidate.Validate(); err != nil {
		return StudioRuntime{}, err
	}
	for i := range rt.Spec.Scenes {
		if rt.Spec.Scenes[i].ID == candidate.ID {
			rt.Spec.Scenes[i] = candidate
		}
	}
	rt.UpdatedAt = time.Now().UTC()
	return cloneRuntime(*rt), nil
}

func runtimeFromSpec(spec StudioSessionSpec) StudioRuntime {
	sources := make(map[string]StudioSource)
	for _, scene := range spec.Scenes {
		for _, source := range scene.Sources {
			sources[source.ID] = source
		}
	}
	return StudioRuntime{Spec: spec, Sources: sources, ProgramScene: spec.ActiveScene, UpdatedAt: time.Now().UTC()}
}

func cloneRuntime(in StudioRuntime) StudioRuntime {
	out := in
	out.Spec.Scenes = append([]StudioScene(nil), in.Spec.Scenes...)
	out.Sources = make(map[string]StudioSource, len(in.Sources))
	for k, v := range in.Sources {
		out.Sources[k] = v
	}
	return out
}

func findScene(scenes []StudioScene, id string) (StudioScene, bool) {
	for _, scene := range scenes {
		if scene.ID == id {
			return scene, true
		}
	}
	return StudioScene{}, false
}
