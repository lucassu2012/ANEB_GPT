package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

const validProfileV2 = `{
  "contract_version": "aneb-profile-v2",
  "profile_id": "wire_contract_probe",
  "version": "1.0.0-test",
  "mode_id": "future_mode",
  "execution_target": "aneb_probe_simulator",
  "claim_scope": "application_end_to_end_to_probe_node",
  "business": {
    "category_id": "future_business",
    "label": "Wire contract probe",
    "future_business_field": {"sentinel": "business-preserved"}
  },
  "measurements": [
    {
      "metric_id": "FUTURE-B01",
      "label": "Future metric",
      "domain": "business",
      "unit": "ratio",
      "measurement_level": "unsupported",
      "formula_id": "future-formula-v1",
      "aggregation": "ratio",
      "direction": "descriptive",
      "required_for_score": false,
      "minimum_sample_count": 1,
      "target_role": "descriptive",
      "quality_target": null,
      "future_metric_field": ["metric-preserved"]
    }
  ],
  "live_presentation": {
    "primary_metric_id": "FUTURE_LIVE",
    "window_ms": 1000,
    "ui_refresh_ms": 250,
    "missing_behavior": "show_unavailable_never_zero",
    "future_live_field": true
  },
  "evaluation": {
    "target_set_id": "future-targets-v1",
    "score_policy_id": "future-score-v1",
    "conclusion_policy_id": "future-conclusions-v1",
    "missing_required_metric": "score_null",
    "invalid_run": "retain_raw_suppress_score",
    "future_evaluation_field": 17
  },
  "trace": {
    "contract_version": "aneb-behavior-trace-v1",
    "seed": 20260716,
    "prng": "pcg32-v1"
  },
  "phases": [
    {
      "type": "behavior_trace",
      "future_phase_field": {"sentinel": "phase-preserved"}
    }
  ],
  "future_top_level_field": {
    "sentinel": "top-level-preserved",
    "future_large_integer": 9007199254740993
  }
}`

func TestProfileV2EndpointPreservesWireDocument(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "profile-v2.json")
	if err := os.WriteFile(path, []byte(validProfileV2), 0o600); err != nil {
		t.Fatal(err)
	}
	profiles, err := loadProfiles(dir)
	if err != nil {
		t.Fatalf("loadProfiles: %v", err)
	}
	profile := profiles["wire_contract_probe"]
	if profile == nil {
		t.Fatal("v2 profile was not loaded")
	}
	if profile.ContractVersion != profileContractV2 || profile.ExecutionTarget != probeTargetV2 {
		t.Fatalf("v2 projection incomplete: %+v", profile)
	}
	if len(profile.Phases) != 1 || profile.Phases[0].Type != "behavior_trace" {
		t.Fatalf("phase projection incomplete: %+v", profile.Phases)
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
		t.Fatalf("decode response: %v", err)
	}
	if len(body.Profiles) != 1 {
		t.Fatalf("got %d profiles, want 1", len(body.Profiles))
	}

	source := decodeJSONDocument(t, []byte(validProfileV2))
	served := decodeJSONDocument(t, body.Profiles[0])
	if !reflect.DeepEqual(served, source) {
		t.Fatalf("served v2 profile changed wire semantics\nsource: %#v\nserved: %#v", source, served)
	}
}

func decodeJSONDocument(t *testing.T, data []byte) any {
	t.Helper()
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		t.Fatal(err)
	}
	return value
}

func TestLoadProfilesRejectsInvalidV2Envelope(t *testing.T) {
	tests := []struct {
		name    string
		mutate  func(map[string]any)
		wantErr string
	}{
		{
			name: "unknown contract",
			mutate: func(value map[string]any) {
				value["contract_version"] = "aneb-profile-v99"
			},
			wantErr: "unsupported contract_version",
		},
		{
			name: "v2 facets without contract",
			mutate: func(value map[string]any) {
				delete(value, "contract_version")
			},
			wantErr: "contract_version is required",
		},
		{
			name: "missing mode",
			mutate: func(value map[string]any) {
				delete(value, "mode_id")
			},
			wantErr: "mode_id must be non-empty",
		},
		{
			name: "wrong execution target",
			mutate: func(value map[string]any) {
				value["execution_target"] = "third_party_service"
			},
			wantErr: "execution_target must be",
		},
		{
			name: "wrong claim scope",
			mutate: func(value map[string]any) {
				value["claim_scope"] = "operator_wide_rating"
			},
			wantErr: "claim_scope must be",
		},
		{
			name: "missing business",
			mutate: func(value map[string]any) {
				delete(value, "business")
			},
			wantErr: "business must be a non-null object",
		},
		{
			name: "empty measurements",
			mutate: func(value map[string]any) {
				value["measurements"] = []any{}
			},
			wantErr: "measurements must be a non-empty array of objects",
		},
		{
			name: "null measurement item",
			mutate: func(value map[string]any) {
				value["measurements"] = []any{nil}
			},
			wantErr: "measurements must be a non-empty array of objects",
		},
		{
			name: "null live presentation",
			mutate: func(value map[string]any) {
				value["live_presentation"] = nil
			},
			wantErr: "live_presentation must be a non-null object",
		},
		{
			name: "empty phases",
			mutate: func(value map[string]any) {
				value["phases"] = []any{}
			},
			wantErr: "phases must be a non-empty array of objects",
		},
		{
			name: "null phase item",
			mutate: func(value map[string]any) {
				value["phases"] = []any{nil}
			},
			wantErr: "phases must be a non-empty array of objects",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			value, ok := decodeJSONDocument(t, []byte(validProfileV2)).(map[string]any)
			if !ok {
				t.Fatal("v2 fixture is not a JSON object")
			}
			tt.mutate(value)
			encoded, err := json.Marshal(value)
			if err != nil {
				t.Fatal(err)
			}
			dir := t.TempDir()
			path := filepath.Join(dir, "invalid.json")
			if err := os.WriteFile(path, encoded, 0o600); err != nil {
				t.Fatal(err)
			}
			_, err = loadProfiles(dir)
			if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
				t.Fatalf("error %v, want substring %q", err, tt.wantErr)
			}
		})
	}
}
