package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"regexp"
	"testing"
)

func TestPrototypeCapabilitiesExposeFrozenContract(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	req := httptest.NewRequest(http.MethodGet, "/api/v1/prototype/capabilities", nil)
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", res.Code, res.Body.String())
	}
	var document map[string]any
	if err := json.Unmarshal(res.Body.Bytes(), &document); err != nil {
		t.Fatalf("decode capabilities: %v", err)
	}
	wantKeys := map[string]bool{
		"schema_version": true, "product_version": true, "protocol_version": true,
		"server_version": true, "server_binary_sha256": true, "claim_scope": true,
		"evidence_mode": true, "impairment_layer": true, "profile_manifest_sha256": true,
		"workload": true, "conditions": true, "evidence_schema_version": true,
		"score_policy_id": true, "terminal_receipt_version": true,
	}
	if len(document) != len(wantKeys) {
		t.Fatalf("capability field count = %d", len(document))
	}
	for key := range document {
		if !wantKeys[key] {
			t.Fatalf("unexpected capability field %q", key)
		}
	}
	assertStringField(t, document, "schema_version", "aneb-prototype-capabilities-0.1")
	assertStringField(t, document, "product_version", "prototype-0.1")
	assertStringField(t, document, "protocol_version", "prototype-stream-0.1")
	assertStringField(t, document, "claim_scope", "application_end_to_end_to_probe_node")
	assertStringField(t, document, "evidence_mode", "synthetic_application_impairment")
	assertStringField(t, document, "impairment_layer", "application")
	assertStringField(t, document, "profile_manifest_sha256", "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc")
	assertStringField(t, document, "evidence_schema_version", "aneb-prototype-evidence-0.1")
	assertStringField(t, document, "score_policy_id", "rpi-0.1")
	assertStringField(t, document, "terminal_receipt_version", "prototype-terminal-receipt-0.1")
	if serverHash, ok := document["server_binary_sha256"].(string); !ok || !regexp.MustCompile(`^[a-f0-9]{64}$`).MatchString(serverHash) {
		t.Fatalf("server_binary_sha256 is not a bare lowercase hash: %#v", document["server_binary_sha256"])
	}

	workload, ok := document["workload"].(map[string]any)
	if !ok {
		t.Fatalf("workload type = %T", document["workload"])
	}
	if workload["id"] != "streaming_text_reference_v0.1" || workload["version"] != "0.1" || workload["content_event_count"] != float64(120) {
		t.Fatalf("workload = %#v", workload)
	}
	conditions, ok := document["conditions"].([]any)
	if !ok || len(conditions) != 3 {
		t.Fatalf("conditions = %#v", document["conditions"])
	}
	wantConditions := []struct {
		id      string
		nominal float64
		hash    string
	}{
		{"baseline_v0.1", 50, "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e"},
		{"slow_v0.1", 125, "b51b27fe8332b3fc8a97472a44312b3001ccd54364a61ed8799816c299d27062"},
		{"unstable_v0.1", 65, "d11dce2a877d7c3772a4552f2d922d5f96730c9a01bb829f0203c65b110a8c58"},
	}
	for i, raw := range conditions {
		condition, ok := raw.(map[string]any)
		if !ok || len(condition) != 4 {
			t.Fatalf("condition %d = %#v", i, raw)
		}
		if condition["id"] != wantConditions[i].id || condition["version"] != "0.1" || condition["nominal_interval_ms"] != wantConditions[i].nominal || condition["schedule_sha256"] != wantConditions[i].hash {
			t.Fatalf("condition %d = %#v", i, condition)
		}
	}
}

func assertStringField(t *testing.T, document map[string]any, name, want string) {
	t.Helper()
	got, ok := document[name].(string)
	if !ok || got != want {
		t.Fatalf("%s = %#v, want %q", name, document[name], want)
	}
}
