package main

import (
	"errors"
	"fmt"
	"sync"
	"time"
)

type StudioRegistry struct {
	mu       sync.RWMutex
	sessions map[string]*StudioRuntime
}

type StudioRuntime struct {
	Spec          StudioSessionSpec       `json:"spec"`
	Sources       map[string]StudioSource  `json:"sources"`
	PreviewScene  string                  `json:"preview_scene"`
	ProgramScene  string                  `json:"program_scene"`
	Transition    StudioTransitionState   `json:"transition"`
	OutputRunning bool                    `json:"output_running"`
	UpdatedAt     time.Time               `json:"updated_at"`
}

type StudioTransitionState struct {
	FromScene string    `json:"from_scene"`
	ToScene   string    `json:"to_scene"`
	Style     string    `json:"style"`
	StartedAt time.Time `json:"started_at,omitempty"`
	DurationMS int      `json:"duration_ms"`
	Active    bool      `json:"active"`
}

func NewStudioRegistry() *StudioRegistry { return &StudioRegistry{sessions: make(map[string]*StudioRuntime)} }

func (r *StudioRegistry) Create(spec StudioSessionSpec) (StudioRuntime, error) {
	if err := spec.Validate(); err != nil { return StudioRuntime{}, err }
	r.mu.Lock(); defer r.mu.Unlock()
	if _, exists := r.sessions[spec.SessionID]; exists { return StudioRuntime{}, fmt.Errorf("studio session %q already exists", spec.SessionID) }
	rt := runtimeFromSpec(spec); r.sessions[spec.SessionID] = &rt; return cloneRuntime(rt), nil
}
func (r *StudioRegistry) Get(id string) (StudioRuntime, bool) { r.mu.RLock(); defer r.mu.RUnlock(); rt, ok := r.sessions[id]; if !ok { return StudioRuntime{}, false }; return cloneRuntime(*rt), true }
func (r *StudioRegistry) Delete(id string) bool { r.mu.Lock(); defer r.mu.Unlock(); if _, ok := r.sessions[id]; !ok { return false }; delete(r.sessions,id); return true }

func (r *StudioRegistry) UpdateScene(sessionID string, scene StudioScene) (StudioRuntime,error) {
	if err:=scene.Validate(); err!=nil{return StudioRuntime{},err}; r.mu.Lock(); defer r.mu.Unlock(); rt,ok:=r.sessions[sessionID]; if !ok{return StudioRuntime{},errors.New("studio session not found")}
	for i:=range rt.Spec.Scenes { if rt.Spec.Scenes[i].ID==scene.ID { rt.Spec.Scenes[i]=scene; rt.UpdatedAt=time.Now().UTC(); return cloneRuntime(*rt),nil } }
	rt.Spec.Scenes=append(rt.Spec.Scenes,scene); rt.UpdatedAt=time.Now().UTC(); return cloneRuntime(*rt),nil
}
func (r *StudioRegistry) DeleteScene(sessionID,sceneID string)(StudioRuntime,error){r.mu.Lock();defer r.mu.Unlock();rt,ok:=r.sessions[sessionID];if !ok{return StudioRuntime{},errors.New("studio session not found")};if rt.Spec.ActiveScene==sceneID||rt.ProgramScene==sceneID{return StudioRuntime{},errors.New("cannot delete program scene")};for i:=range rt.Spec.Scenes{if rt.Spec.Scenes[i].ID==sceneID{rt.Spec.Scenes=append(rt.Spec.Scenes[:i],rt.Spec.Scenes[i+1:]...);rt.UpdatedAt=time.Now().UTC();return cloneRuntime(*rt),nil}};return StudioRuntime{},errors.New("scene not found")}
func (r *StudioRegistry) SetPreviewScene(sessionID,sceneID string)(StudioRuntime,error){r.mu.Lock();defer r.mu.Unlock();rt,ok:=r.sessions[sessionID];if !ok{return StudioRuntime{},errors.New("studio session not found")};if _,ok:=findScene(rt.Spec.Scenes,sceneID);!ok{return StudioRuntime{},errors.New("scene not found")};rt.PreviewScene=sceneID;rt.UpdatedAt=time.Now().UTC();return cloneRuntime(*rt),nil}
func (r *StudioRegistry) ActivateScene(sessionID,sceneID string)(StudioRuntime,error){return r.TransitionScene(sessionID,sceneID,"cut",0)}
func (r *StudioRegistry) TransitionScene(sessionID,sceneID,style string,durationMS int)(StudioRuntime,error){r.mu.Lock();defer r.mu.Unlock();rt,ok:=r.sessions[sessionID];if !ok{return StudioRuntime{},errors.New("studio session not found")};if _,ok:=findScene(rt.Spec.Scenes,sceneID);!ok{return StudioRuntime{},errors.New("scene not found")};if durationMS<0||durationMS>60000{return StudioRuntime{},errors.New("transition duration must be between 0 and 60000 ms")};from:=rt.ProgramScene;rt.PreviewScene=sceneID;rt.Transition=StudioTransitionState{FromScene:from,ToScene:sceneID,Style:style,DurationMS:durationMS,StartedAt:time.Now().UTC(),Active:durationMS>0};rt.Spec.ActiveScene=sceneID;rt.ProgramScene=sceneID;rt.UpdatedAt=time.Now().UTC();return cloneRuntime(*rt),nil}
func (r *StudioRegistry) RegisterSource(sessionID string,source StudioSource)(StudioRuntime,error){if err:=source.Validate();err!=nil{return StudioRuntime{},err};r.mu.Lock();defer r.mu.Unlock();rt,ok:=r.sessions[sessionID];if !ok{return StudioRuntime{},errors.New("studio session not found")};if _,exists:=rt.Sources[source.ID];exists{return StudioRuntime{},errors.New("source already registered")};rt.Sources[source.ID]=source;rt.UpdatedAt=time.Now().UTC();return cloneRuntime(*rt),nil}
func (r *StudioRegistry) UpdateSource(sessionID string,source StudioSource)(StudioRuntime,error){if err:=source.Validate();err!=nil{return StudioRuntime{},err};r.mu.Lock();defer r.mu.Unlock();rt,ok:=r.sessions[sessionID];if !ok{return StudioRuntime{},errors.New("studio session not found")};if _,ok:=rt.Sources[source.ID];!ok{return StudioRuntime{},errors.New("source not found")};rt.Sources[source.ID]=source;for i:=range rt.Spec.Scenes{for j:=range rt.Spec.Scenes[i].Sources{if rt.Spec.Scenes[i].Sources[j].ID==source.ID{rt.Spec.Scenes[i].Sources[j]=source}}};rt.UpdatedAt=time.Now().UTC();return cloneRuntime(*rt),nil}
func (r *StudioRegistry) RemoveSource(sessionID,sourceID string)(StudioRuntime,error){r.mu.Lock();defer r.mu.Unlock();rt,ok:=r.sessions[sessionID];if !ok{return StudioRuntime{},errors.New("studio session not found")};if _,ok:=rt.Sources[sourceID];!ok{return StudioRuntime{},errors.New("source not found")};for _,sc:=range rt.Spec.Scenes{for _,src:=range sc.Sources{if src.ID==sourceID{return StudioRuntime{},errors.New("source is still referenced by a scene")}}};delete(rt.Sources,sourceID);rt.UpdatedAt=time.Now().UTC();return cloneRuntime(*rt),nil}
func (r *StudioRegistry) SetOutput(sessionID string,output StudioOutputConfig,running bool)(StudioRuntime,error){if err:=output.Validate();err!=nil{return StudioRuntime{},err};r.mu.Lock();defer r.mu.Unlock();rt,ok:=r.sessions[sessionID];if !ok{return StudioRuntime{},errors.New("studio session not found")};rt.Spec.Output=output;rt.OutputRunning=running;rt.UpdatedAt=time.Now().UTC();return cloneRuntime(*rt),nil}
func (r *StudioRegistry) SetAudio(sessionID string,audio []StudioAudioBus)(StudioRuntime,error){r.mu.Lock();defer r.mu.Unlock();rt,ok:=r.sessions[sessionID];if !ok{return StudioRuntime{},errors.New("studio session not found")};active,ok:=findScene(rt.Spec.Scenes,rt.Spec.ActiveScene);if !ok{return StudioRuntime{},errors.New("active scene not found")};active.Audio=append([]StudioAudioBus(nil),audio...);if err:=active.Validate();err!=nil{return StudioRuntime{},err};for i:=range rt.Spec.Scenes{if rt.Spec.Scenes[i].ID==active.ID{rt.Spec.Scenes[i]=active}};rt.UpdatedAt=time.Now().UTC();return cloneRuntime(*rt),nil}
func runtimeFromSpec(spec StudioSessionSpec)StudioRuntime{sources:=map[string]StudioSource{};for _,sc:=range spec.Scenes{for _,src:=range sc.Sources{sources[src.ID]=src}};return StudioRuntime{Spec:spec,Sources:sources,PreviewScene:spec.ActiveScene,ProgramScene:spec.ActiveScene,UpdatedAt:time.Now().UTC()}}
func cloneRuntime(in StudioRuntime)StudioRuntime{out:=in;out.Spec.Scenes=append([]StudioScene(nil),in.Spec.Scenes...);out.Sources=map[string]StudioSource{};for k,v:=range in.Sources{out.Sources[k]=v};return out}
func findScene(scenes []StudioScene,id string)(StudioScene,bool){for _,s:=range scenes{if s.ID==id{return s,true}};return StudioScene{},false}
