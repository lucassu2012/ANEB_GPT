package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAPIRequiresBearerTokenAndStartsAllowlistedProfile(t *testing.T) {
	profile := validProfile()
	manager, err := NewManager(context.Background(), map[string]Profile{profile.Ref(): profile}, &fakeController{}, &memoryAuditor{})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	token := "0123456789abcdef0123456789abcdef"
	handler, err := (API{Manager: manager, Token: token}).Handler()
	if err != nil {
		t.Fatal(err)
	}

	health := httptest.NewRecorder()
	handler.ServeHTTP(health, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if health.Code != http.StatusOK || health.Header().Get("X-Aneb-Gateway") != GatewayVersion {
		t.Fatalf("health status=%d headers=%v", health.Code, health.Header())
	}

	unauthorized := httptest.NewRecorder()
	handler.ServeHTTP(unauthorized, httptest.NewRequest(http.MethodGet, "/v1/profiles", nil))
	if unauthorized.Code != http.StatusUnauthorized {
		t.Fatalf("unauthorized status=%d", unauthorized.Code)
	}

	body, _ := json.Marshal(map[string]string{"run_id": "run-api", "profile_ref": profile.Ref()})
	request := httptest.NewRequest(http.MethodPost, "/v1/experiments", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+token)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusAccepted {
		t.Fatalf("start status=%d body=%s", response.Code, response.Body.String())
	}
	var experiment Experiment
	if err := json.NewDecoder(response.Body).Decode(&experiment); err != nil {
		t.Fatal(err)
	}
	if experiment.RunID != "run-api" || experiment.ProfileRef != profile.Ref() || experiment.Phase != "scheduled" {
		t.Fatalf("experiment=%+v", experiment)
	}
}

func TestAPIRejectsUnknownFieldsAndUnknownProfiles(t *testing.T) {
	profile := validProfile()
	manager, err := NewManager(context.Background(), map[string]Profile{profile.Ref(): profile}, &fakeController{}, &memoryAuditor{})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	token := "0123456789abcdef0123456789abcdef"
	handler, _ := (API{Manager: manager, Token: token}).Handler()

	for _, body := range []string{
		`{"run_id":"run-extra","profile_ref":"test_profile@1.0.0","unexpected":true}`,
		`{"run_id":"run-missing","profile_ref":"missing@1.0.0"}`,
	} {
		request := httptest.NewRequest(http.MethodPost, "/v1/experiments", bytes.NewBufferString(body))
		request.Header.Set("Authorization", "Bearer "+token)
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusBadRequest && response.Code != http.StatusNotFound {
			t.Fatalf("body=%s status=%d", body, response.Code)
		}
	}
}
