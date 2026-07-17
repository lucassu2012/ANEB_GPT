package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestAPIRequiresBearerTokenAndStartsAllowlistedProfile(t *testing.T) {
	profile := validProfile()
	manager, err := NewManager(context.Background(), map[string]Profile{profile.Ref(): profile}, &fakeController{}, &memoryAuditor{})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	token := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
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
	token := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
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

func TestAPIRestrictsManagementClientsToExclusiveSubnet(t *testing.T) {
	profile := validProfile()
	manager, err := NewManager(context.Background(), map[string]Profile{profile.Ref(): profile}, &fakeController{}, &memoryAuditor{})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	_, network, _ := net.ParseCIDR("192.168.77.0/24")
	handler, err := (API{
		Manager:             manager,
		Token:               "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
		AllowedClientSubnet: network,
	}).Handler()
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	request.RemoteAddr = "192.168.78.2:12345"
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusForbidden {
		t.Fatalf("outside client status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	request = httptest.NewRequest(http.MethodGet, "/healthz", nil)
	request.RemoteAddr = "192.168.77.2:12345"
	recorder = httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusOK {
		t.Fatalf("inside client status=%d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestHealthTurnsDegradedWhileCleanupFailureIsLatched(t *testing.T) {
	controller := &fakeController{}
	profile := validProfile()
	manager, err := NewManager(context.Background(), map[string]Profile{profile.Ref(): profile}, controller, &memoryAuditor{})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	controller.mu.Lock()
	controller.clearErr = errors.New("qdisc remains")
	controller.mu.Unlock()
	experiment, err := manager.Start("health-latch", profile.Ref())
	if err != nil {
		t.Fatal(err)
	}
	waitForPhase(t, manager, experiment.ExperimentID, "cleanup_failed", time.Second)
	handler, err := (API{
		Manager: manager,
		Token:   "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
	}).Handler()
	if err != nil {
		t.Fatal(err)
	}
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if recorder.Code != http.StatusServiceUnavailable || !strings.Contains(recorder.Body.String(), "degraded_cleanup_failed") {
		t.Fatalf("health status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	controller.mu.Lock()
	controller.clearErr = nil
	controller.mu.Unlock()
}

func TestAPIStartReplayIsIdempotentAndRunProfileConflictIsExplicit(t *testing.T) {
	firstProfile := validProfile()
	secondProfile := validProfile()
	secondProfile.ProfileID = "api_second_profile"
	manager, err := NewManager(context.Background(), map[string]Profile{
		firstProfile.Ref(): firstProfile, secondProfile.Ref(): secondProfile,
	}, &fakeController{}, &memoryAuditor{})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	token := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	handler, err := (API{Manager: manager, Token: token}).Handler()
	if err != nil {
		t.Fatal(err)
	}
	post := func(profileRef string) *httptest.ResponseRecorder {
		body, _ := json.Marshal(map[string]string{"run_id": "api-idempotent", "profile_ref": profileRef})
		request := httptest.NewRequest(http.MethodPost, "/v1/experiments", bytes.NewReader(body))
		request.Header.Set("Authorization", "Bearer "+token)
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		return response
	}
	first := post(firstProfile.Ref())
	replayed := post(firstProfile.Ref())
	if first.Code != http.StatusAccepted || replayed.Code != http.StatusAccepted {
		t.Fatalf("start=%d replay=%d", first.Code, replayed.Code)
	}
	var firstExperiment, replayedExperiment Experiment
	if err := json.NewDecoder(first.Body).Decode(&firstExperiment); err != nil {
		t.Fatal(err)
	}
	if err := json.NewDecoder(replayed.Body).Decode(&replayedExperiment); err != nil {
		t.Fatal(err)
	}
	if firstExperiment.ExperimentID != replayedExperiment.ExperimentID {
		t.Fatalf("start id=%s replay id=%s", firstExperiment.ExperimentID, replayedExperiment.ExperimentID)
	}
	conflict := post(secondProfile.Ref())
	if conflict.Code != http.StatusConflict || !strings.Contains(conflict.Body.String(), "run_id_profile_conflict") {
		t.Fatalf("conflict status=%d body=%s", conflict.Code, conflict.Body.String())
	}
}
