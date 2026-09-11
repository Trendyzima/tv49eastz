package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestStudioRoutesRejectsUnauthenticatedMutation(t *testing.T) {
	g := &Gateway{cfg: Config{APIKey: "test-api-key"}}
	req := httptest.NewRequest(http.MethodPost, "/v1/studio/sessions", nil)
	res := httptest.NewRecorder()
	g.studioRoutes(res, req)
	if res.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", res.Code)
	}
}

func TestStudioOwnerCannotBeImpersonatedByAnotherPrincipal(t *testing.T) {
	id := "security-owner-studio"
	if _, err := defaultStudioRegistry.Create(validStudioSpec("principal-a", id)); err != nil {
		t.Fatal(err)
	}
	defer defaultStudioRegistry.Delete(id)
	g := &Gateway{}
	if g.studioOwner(id, "principal-b") {
		t.Fatal("principal-b was authorized to mutate principal-a studio")
	}
}
