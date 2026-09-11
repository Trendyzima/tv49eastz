package main

import (
 "encoding/json"
 "net/http"
 "strings"
)

var defaultStudioRegistry = NewStudioRegistry()

func (g *Gateway) studioRoutes(w http.ResponseWriter,r *http.Request){
 p,err:=authenticate(r,g.cfg.APIKey);if err!=nil{g.denied.Add(1);http.Error(w,"unauthorized",401);return}
 path:=strings.TrimPrefix(r.URL.Path,"/v1/studio/");parts:=strings.Split(strings.Trim(path,"/"),"/")
 if len(parts)==1&&parts[0]=="capabilities"&&r.Method==http.MethodGet{w.Header().Set("Content-Type","application/json");json.NewEncoder(w).Encode(DefaultStudioFeatureSet());return}
 if len(parts)==0||parts[0]!="sessions"{http.NotFound(w,r);return}
 if len(parts)==1{if r.Method!=http.MethodPost{methodNotAllowed(w);return};g.studioCreate(w,r,p);return}
 if len(parts)==2{switch r.Method{case http.MethodGet:g.studioGet(w,r,p,parts[1]);case http.MethodDelete:g.studioDelete(w,r,p,parts[1]);default:methodNotAllowed(w)};return}
 sid,action:=parts[1],parts[2];if !g.studioOwner(sid,p.UserID){http.Error(w,"forbidden",403);return}
 switch action{
 case "scenes":g.studioSceneRoutes(w,r,sid,parts[3:])
 case "preview":if r.Method!=http.MethodPost{methodNotAllowed(w);return};var q struct{SceneID string `json:"scene_id"`};if !decodeJSON(w,r,&q){return};v,e:=defaultStudioRegistry.SetPreviewScene(sid,q.SceneID);g.writeStudio(w,v,e)
 case "activate":if r.Method!=http.MethodPost{methodNotAllowed(w);return};var q struct{SceneID string `json:"scene_id"`;Style string `json:"style"`;DurationMS int `json:"duration_ms"`};if !decodeJSON(w,r,&q){return};v,e:=defaultStudioRegistry.TransitionScene(sid,q.SceneID,q.Style,q.DurationMS);g.writeStudio(w,v,e)
 case "sources":g.studioSourceRoutes(w,r,sid,parts[3:])
 case "output":if r.Method!=http.MethodPut{methodNotAllowed(w);return};var q struct{Output StudioOutputConfig `json:"output"`;Running bool `json:"running"`};if !decodeJSON(w,r,&q){return};v,e:=defaultStudioRegistry.SetOutput(sid,q.Output,q.Running);g.writeStudio(w,v,e)
 case "audio":if r.Method!=http.MethodPut{methodNotAllowed(w);return};var q struct{Audio []StudioAudioBus `json:"audio"`};if !decodeJSON(w,r,&q){return};v,e:=defaultStudioRegistry.SetAudio(sid,q.Audio);g.writeStudio(w,v,e)
 default:http.NotFound(w,r)
 }
}
func (g *Gateway) studioSourceRoutes(w http.ResponseWriter,r *http.Request,sid string,rest []string){if len(rest)==0&&r.Method==http.MethodPost{var s StudioSource;if !decodeJSON(w,r,&s){return};v,e:=defaultStudioRegistry.RegisterSource(sid,s);g.writeStudio(w,v,e);return};if len(rest)==1{if r.Method==http.MethodPut{var s StudioSource;if !decodeJSON(w,r,&s){return};v,e:=defaultStudioRegistry.UpdateSource(sid,s);g.writeStudio(w,v,e);return};if r.Method==http.MethodDelete{v,e:=defaultStudioRegistry.RemoveSource(sid,rest[0]);g.writeStudio(w,v,e);return}};methodNotAllowed(w)}
func (g *Gateway) studioCreate(w http.ResponseWriter,r *http.Request,p Principal){var s StudioSessionSpec;if !decodeJSON(w,r,&s){return};s.OwnerID=p.UserID;if s.SessionID==""{http.Error(w,"session_id is required",400);return};v,e:=defaultStudioRegistry.Create(s);g.writeStudio(w,v,e)}
func (g *Gateway) studioGet(w http.ResponseWriter,_ *http.Request,p Principal,id string){v,ok:=defaultStudioRegistry.Get(id);if !ok{http.NotFound(w,nil);return};if v.Spec.OwnerID!=p.UserID{http.Error(w,"forbidden",403);return};g.writeStudio(w,v,nil)}
func (g *Gateway) studioDelete(w http.ResponseWriter,_ *http.Request,p Principal,id string){v,ok:=defaultStudioRegistry.Get(id);if !ok{http.NotFound(w,nil);return};if v.Spec.OwnerID!=p.UserID{http.Error(w,"forbidden",403);return};defaultStudioRegistry.Delete(id);w.WriteHeader(204)}
func (g *Gateway) studioOwner(id,uid string)bool{v,ok:=defaultStudioRegistry.Get(id);return ok&&v.Spec.OwnerID==uid}
func (g *Gateway) studioSceneRoutes(w http.ResponseWriter,r *http.Request,sid string,rest []string){if len(rest)==0&&r.Method==http.MethodPut{var s StudioScene;if !decodeJSON(w,r,&s){return};v,e:=defaultStudioRegistry.UpdateScene(sid,s);g.writeStudio(w,v,e);return};if len(rest)==1&&r.Method==http.MethodDelete{v,e:=defaultStudioRegistry.DeleteScene(sid,rest[0]);g.writeStudio(w,v,e);return};methodNotAllowed(w)}
func (g *Gateway) writeStudio(w http.ResponseWriter,v StudioRuntime,e error){if e!=nil{code:=400;if strings.Contains(e.Error(),"session not found"){code=404};http.Error(w,e.Error(),code);return};w.Header().Set("Content-Type","application/json");w.Header().Set("Cache-Control","no-store");json.NewEncoder(w).Encode(v)}
func decodeJSON(w http.ResponseWriter,r *http.Request,t any)bool{if r.Body==nil||r.ContentLength>2<<20{http.Error(w,"invalid request body",400);return false};d:=json.NewDecoder(http.MaxBytesReader(w,r.Body,2<<20));d.DisallowUnknownFields();if err:=d.Decode(t);err!=nil{http.Error(w,"invalid JSON body",400);return false};return true}
func methodNotAllowed(w http.ResponseWriter){http.Error(w,"method not allowed",405)}
