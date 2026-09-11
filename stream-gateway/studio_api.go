package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
)

var defaultStudioRegistry = NewStudioRegistry()

func (g *Gateway) studioRoutes(w http.ResponseWriter, r *http.Request) {
	principal, err := authenticate(r, g.cfg.APIKey)
	if err != nil {
		g.denied.Add(1)
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	path := strings.TrimPrefix(r.URL.Path, "/v1/studio/")
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) == 0 || parts[0] != "sessions" {
		http.NotFound(w, r)
		return
	}
	if len(parts) == 2 && parts[1] != "" {
		switch r.Method {
		case http.MethodGet:
			g.studioGet(w, r, principal, parts[1])
		case http.MethodDelete:
			g.studioDelete(w, r, principal, parts[1])
		default:
			methodNotAllowed(w)
		}
		return
	}
	if len(parts) == 1 {
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
			return
		}
		g.studioCreate(w, r, principal)
		return
	}
	if len(parts) < 3 || parts[1] == "" {
		http.NotFound(w, r)
		return
	}
	sessionID, action := parts[1], parts[2]
	if !g.studioOwner(sessionID, principal.UserID) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	switch action {
	case "scenes":
		g.studioSceneRoutes(w, r, sessionID, parts[3:])
	case "activate":
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
			return
		}
		var req struct{ SceneID string `json:"scene_id"` }
		if !decodeJSON(w, r, &req) || strings.TrimSpace(req.SceneID) == "" {
			return
		}
		g.writeStudio(w, defaultStudioRegistry.ActivateScene(sessionID, req.SceneID))
	case "sources":
		if r.Method != http.MethodPost {
			methodNotAllowed(w)
			return
		}
		var source StudioSource
		if !decodeJSON(w, r, &source) { return }
		g.writeStudio(w, defaultStudioRegistry.RegisterSource(sessionID, source))
	case "output":
		if r.Method != http.MethodPut {
			methodNotAllowed(w)
			return
		}
		var req struct {
			Output StudioOutputConfig `json:"output"`
			Running bool `json:"running"`
		}
		if !decodeJSON(w, r, &req) { return }
		g.writeStudio(w, defaultStudioRegistry.SetOutput(sessionID, req.Output, req.Running))
	case "audio":
		if r.Method != http.MethodPut {
			methodNotAllowed(w)
			return
		}
		var req struct{ Audio []StudioAudioBus `json:"audio"` }
		if !decodeJSON(w, r, &req) { return }
		g.writeStudio(w, defaultStudioRegistry.SetAudio(sessionID, req.Audio))
	default:
		http.NotFound(w, r)
	}
}

func (g *Gateway) studioCreate(w http.ResponseWriter, r *http.Request, p Principal) {
	var spec StudioSessionSpec
	if !decodeJSON(w, r, &spec) { return }
	spec.OwnerID = p.UserID
	if spec.SessionID == "" {
		http.Error(w, "session_id is required", http.StatusBadRequest)
		return
	}
	rt, err := defaultStudioRegistry.Create(spec)
	g.writeStudio(w, rt, err)
}

func (g *Gateway) studioGet(w http.ResponseWriter, _ *http.Request, p Principal, id string) {
	rt, ok := defaultStudioRegistry.Get(id)
	if !ok { http.NotFound(w, nil); return }
	if rt.Spec.OwnerID != p.UserID { http.Error(w, "forbidden", 403); return }
	g.writeStudio(w, rt, nil)
}

func (g *Gateway) studioDelete(w http.ResponseWriter, _ *http.Request, p Principal, id string) {
	rt, ok := defaultStudioRegistry.Get(id)
	if !ok { http.NotFound(w, nil); return }
	if rt.Spec.OwnerID != p.UserID { http.Error(w, "forbidden", 403); return }
	if !defaultStudioRegistry.Delete(id) { http.NotFound(w, nil); return }
	w.WriteHeader(http.StatusNoContent)
}

func (g *Gateway) studioOwner(id, userID string) bool {
	rt, ok := defaultStudioRegistry.Get(id)
	return ok && rt.Spec.OwnerID == userID
}

func (g *Gateway) studioSceneRoutes(w http.ResponseWriter, r *http.Request, sessionID string, rest []string) {
	if len(rest) == 0 {
		if r.Method != http.MethodPut { methodNotAllowed(w); return }
		var scene StudioScene
		if !decodeJSON(w, r, &scene) { return }
		g.writeStudio(w, defaultStudioRegistry.UpdateScene(sessionID, scene))
		return
	}
	if len(rest) == 1 && rest[0] != "" {
		if r.Method != http.MethodDelete { methodNotAllowed(w); return }
		g.writeStudio(w, defaultStudioRegistry.DeleteScene(sessionID, rest[0]))
		return
	}
	http.NotFound(w, r)
}

func (g *Gateway) writeStudio(w http.ResponseWriter, value StudioRuntime, err error) {
	if err != nil {
		status := http.StatusBadRequest
		if errors.Is(err, errors.New("studio session not found")) { status = http.StatusNotFound }
		http.Error(w, err.Error(), status)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(value)
}

func decodeJSON(w http.ResponseWriter, r *http.Request, target any) bool {
	if r.Body == nil || r.ContentLength > 2<<20 {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return false
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2<<20))
	dec.DisallowUnknownFields()
	if err := dec.Decode(target); err != nil {
		http.Error(w, "invalid JSON body", http.StatusBadRequest)
		return false
	}
	return true
}

func methodNotAllowed(w http.ResponseWriter) { http.Error(w, "method not allowed", http.StatusMethodNotAllowed) }
