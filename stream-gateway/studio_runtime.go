package main

import (
	"context"
	"errors"
	"sync"
	"time"
)

// StudioFrame is the single program-frame contract shared by recording and live sinks.
type StudioFrame struct {
	SessionID string
	SceneID   string
	Sequence  uint64
	PTS       time.Duration
	Payload   []byte
}

type StudioFrameSink interface { WriteFrame(context.Context, StudioFrame) error }

type StudioFrameFanout struct { mu sync.RWMutex; sinks map[string]StudioFrameSink }
func NewStudioFrameFanout() *StudioFrameFanout { return &StudioFrameFanout{sinks: map[string]StudioFrameSink{}} }
func (f *StudioFrameFanout) Attach(name string, sink StudioFrameSink) error { if name==""||sink==nil{return errors.New("sink name and implementation are required")}; f.mu.Lock(); defer f.mu.Unlock(); if _,ok:=f.sinks[name];ok{return errors.New("sink already attached")}; f.sinks[name]=sink; return nil }
func (f *StudioFrameFanout) Detach(name string) { f.mu.Lock(); defer f.mu.Unlock(); delete(f.sinks,name) }
func (f *StudioFrameFanout) Publish(ctx context.Context, frame StudioFrame) error { f.mu.RLock(); sinks:=make([]StudioFrameSink,0,len(f.sinks)); for _,s:=range f.sinks{sinks=append(sinks,s)}; f.mu.RUnlock(); var first error; for _,s:=range sinks{if err:=s.WriteFrame(ctx,frame);err!=nil&&first==nil{first=err}}; return first }

type StudioCompositor interface { Compose(context.Context, StudioRuntime) (StudioFrame,error) }
type RegistryCompositor struct { fanout *StudioFrameFanout; seq uint64; mu sync.Mutex }
func NewRegistryCompositor(f *StudioFrameFanout) *RegistryCompositor { return &RegistryCompositor{fanout:f} }
func (c *RegistryCompositor) Compose(ctx context.Context, rt StudioRuntime) (StudioFrame,error) { c.mu.Lock(); c.seq++; seq:=c.seq; c.mu.Unlock(); return StudioFrame{SessionID:rt.Spec.SessionID,SceneID:rt.ProgramScene,Sequence:seq,PTS:time.Duration(seq)*time.Millisecond},nil }
func (c *RegistryCompositor) ComposeAndPublish(ctx context.Context, rt StudioRuntime) error { frame,err:=c.Compose(ctx,rt); if err!=nil{return err}; return c.fanout.Publish(ctx,frame) }

type MemoryFrameSink struct { mu sync.Mutex; Frames []StudioFrame }
func (s *MemoryFrameSink) WriteFrame(_ context.Context, f StudioFrame) error { s.mu.Lock(); defer s.mu.Unlock(); f.Payload=append([]byte(nil),f.Payload...); s.Frames=append(s.Frames,f); return nil }

// StudioSessionStore is a persistence boundary; the in-memory implementation is deterministic for gateway tests.
type StudioSessionStore interface { Save(StudioRuntime) error; Load(string)(StudioRuntime,bool); Delete(string) error; Expire(time.Time) int }
type MemoryStudioSessionStore struct { mu sync.Mutex; values map[string]StudioRuntime; updated map[string]time.Time; ttl time.Duration }
func NewMemoryStudioSessionStore(ttl time.Duration)*MemoryStudioSessionStore{return &MemoryStudioSessionStore{values:map[string]StudioRuntime{},updated:map[string]time.Time{},ttl:ttl}}
func (s *MemoryStudioSessionStore) Save(v StudioRuntime)error{s.mu.Lock();defer s.mu.Unlock();s.values[v.Spec.SessionID]=cloneRuntime(v);s.updated[v.Spec.SessionID]=v.UpdatedAt;return nil}
func (s *MemoryStudioSessionStore) Load(id string)(StudioRuntime,bool){s.mu.Lock();defer s.mu.Unlock();v,ok:=s.values[id];if !ok{return StudioRuntime{},false};return cloneRuntime(v),true}
func (s *MemoryStudioSessionStore) Delete(id string)error{s.mu.Lock();defer s.mu.Unlock();delete(s.values,id);delete(s.updated,id);return nil}
func (s *MemoryStudioSessionStore) Expire(now time.Time)int{s.mu.Lock();defer s.mu.Unlock();n:=0;for id,t:=range s.updated{if s.ttl>0&&now.Sub(t)>=s.ttl{delete(s.values,id);delete(s.updated,id);n++}};return n}
