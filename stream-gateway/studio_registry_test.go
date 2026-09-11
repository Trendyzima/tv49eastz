package main

import "testing"

func validRegistryStudioSpec(owner, id string) StudioSessionSpec {
	return StudioSessionSpec{
		SessionID: id,
		OwnerID: owner,
		Scenes: []StudioScene{{
			ID: "scene-main", Name: "Main", Layout: LayoutFullscreen,
			Sources: []StudioSource{{ID: "camera-1", Kind: SourceCamera, Name: "Camera", Position: StudioRect{X: 0, Y: 0, W: 1, H: 1}}},
		}},
		ActiveScene: "scene-main",
		Output: StudioOutputConfig{Width: 1920, Height: 1080, FPS: 30, VideoBitrate: 5_000_000, AudioBitrate: 128_000, RecordLocal: true},
	}
}

func TestStudioRegistryLifecycle(t *testing.T) {
	r := NewStudioRegistry()
	created, err := r.Create(validRegistryStudioSpec("owner-a", "studio-a"))
	if err != nil { t.Fatalf("create: %v", err) }
	if created.ProgramScene != "scene-main" { t.Fatalf("unexpected program scene: %q", created.ProgramScene) }
	if _, err := r.Create(validRegistryStudioSpec("owner-a", "studio-a")); err == nil { t.Fatal("duplicate session accepted") }
	if _, err := r.ActivateScene("studio-a", "missing"); err == nil { t.Fatal("missing scene activated") }
	if _, err := r.RegisterSource("studio-a", StudioSource{ID: "video-1", Kind: SourceVideo, Name: "Clip", URI: "content://clip", Position: StudioRect{X: .7, Y: .7, W: .25, H: .25}}); err != nil { t.Fatalf("register source: %v", err) }
	if got, ok := r.Get("studio-a"); !ok || got.Sources["video-1"].URI != "content://clip" { t.Fatal("source was not registered") }
	if !r.Delete("studio-a") { t.Fatal("delete failed") }
	if _, ok := r.Get("studio-a"); ok { t.Fatal("deleted session still visible") }
}

func TestStudioOwnerIsolation(t *testing.T) {
	id := "owner-isolation-studio"
	if _, err := defaultStudioRegistry.Create(validRegistryStudioSpec("owner-a", id)); err != nil { t.Fatal(err) }
	defer defaultStudioRegistry.Delete(id)
	g := &Gateway{}
	if g.studioOwner(id, "owner-b") { t.Fatal("foreign owner authorized") }
	if !g.studioOwner(id, "owner-a") { t.Fatal("owner rejected") }
}

func TestStudioRegistryRejectsInvalidAudioAndOutput(t *testing.T) {
	r := NewStudioRegistry()
	if _, err := r.Create(validRegistryStudioSpec("owner-a", "studio-validation")); err != nil { t.Fatal(err) }
	if _, err := r.SetOutput("studio-validation", StudioOutputConfig{Width: 1, Height: 1, FPS: 30, VideoBitrate: 1, AudioBitrate: 1, RecordLocal: true}, false); err == nil { t.Fatal("invalid output accepted") }
	if _, err := r.SetAudio("studio-validation", []StudioAudioBus{{SourceID: "not-present", Volume: 1}}); err == nil { t.Fatal("audio for unknown source accepted") }
}

func TestStudioRegistryReturnsDeepCopies(t *testing.T) {
	r := NewStudioRegistry()
	if _, err := r.Create(validRegistryStudioSpec("owner-a", "studio-copy")); err != nil { t.Fatal(err) }
	first, ok := r.Get("studio-copy")
	if !ok { t.Fatal("session missing") }
	first.Spec.Scenes[0].Name = "mutated externally"
	first.Spec.Scenes[0].Sources[0].Name = "mutated source"
	first.Sources["camera-1"] = StudioSource{ID: "camera-1", Kind: SourceCamera, Name: "mutated map", Position: StudioRect{X: 0, Y: 0, W: 1, H: 1}}
	second, ok := r.Get("studio-copy")
	if !ok { t.Fatal("session missing after mutation") }
	if second.Spec.Scenes[0].Name != "Main" { t.Fatal("scene slice leaked registry state") }
	if second.Spec.Scenes[0].Sources[0].Name != "Camera" { t.Fatal("nested source slice leaked registry state") }
	if second.Sources["camera-1"].Name != "Camera" { t.Fatal("source map leaked registry state") }
}
