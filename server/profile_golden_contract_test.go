package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"testing"
)

const sharedTokenProfileGolden = "../testdata/profile-v2/golden/token_multimodal_standard.seed-20260716.json"

func TestSharedProfileV2GoldenLoadsAndServesWithoutLoss(t *testing.T) {
	profiles, err := loadProfiles("../testdata/profile-v2/golden")
	if err != nil {
		t.Fatalf("load shared Profile v2 goldens: %v", err)
	}
	profile := profiles["token_multimodal_standard"]
	if profile == nil {
		t.Fatal("shared token Profile v2 golden was not loaded")
	}
	if profile.ContractVersion != profileContractV2 {
		t.Fatalf("contract_version %q, want %q", profile.ContractVersion, profileContractV2)
	}
	if profile.ExecutionTarget != probeTargetV2 || profile.ClaimScope != probeClaimScopeV2 {
		t.Fatalf("v2 claim projection is invalid: %+v", profile)
	}
	if len(profile.Phases) != 1 || profile.Phases[0].Type != "behavior_trace" {
		t.Fatalf("behavior_trace phase projection is invalid: %+v", profile.Phases)
	}

	a := &app{profiles: profiles, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/api/v1/profiles")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}
	var body struct {
		Profiles []json.RawMessage `json:"profiles"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("decode profiles response: %v", err)
	}
	if len(body.Profiles) != 1 {
		t.Fatalf("served %d profiles, want 1", len(body.Profiles))
	}

	sourceBytes, err := os.ReadFile(sharedTokenProfileGolden)
	if err != nil {
		t.Fatal(err)
	}
	source := decodeJSONDocument(t, sourceBytes)
	served := decodeJSONDocument(t, body.Profiles[0])
	if !reflect.DeepEqual(served, source) {
		t.Fatalf("served shared golden changed wire semantics\nsource: %#v\nserved: %#v", source, served)
	}
}
