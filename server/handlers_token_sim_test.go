package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func tokenSimRequestBody(t *testing.T, plan tokenSimTaskPlan, payloadBytes int) []byte {
	t.Helper()
	encoded, err := json.Marshal(plan)
	if err != nil {
		t.Fatal(err)
	}
	var body bytes.Buffer
	if err := binary.Write(&body, binary.BigEndian, uint32(len(encoded))); err != nil {
		t.Fatal(err)
	}
	body.Write(encoded)
	body.Write(bytes.Repeat([]byte{0x5a}, payloadBytes))
	return body.Bytes()
}

func validTokenSimPlan() tokenSimTaskPlan {
	return tokenSimTaskPlan{
		ContractVersion:    tokenSimTaskContract,
		TaskID:             "task-0001",
		WorkloadKind:       "text",
		Seed:               20260716,
		ProcessingMs:       1,
		UploadPayloadBytes: 1024,
		TokenIntervalsMs:   []float64{0, 2, 3},
		TokenSizesBytes:    []int{11, 12, 13},
	}
}

func TestTokenSimExecutesExactPlan(t *testing.T) {
	server := httptest.NewServer((&app{}).routes())
	defer server.Close()
	plan := validTokenSimPlan()
	body := tokenSimRequestBody(t, plan, int(plan.UploadPayloadBytes))
	resp, err := http.Post(server.URL+"/api/v1/token-sim", "application/octet-stream", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	encoded, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	text := string(encoded)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status=%d body=%s", resp.StatusCode, text)
	}
	if strings.Count(text, "event: token\n") != 3 {
		t.Fatalf("token event count mismatch: %s", text)
	}
	for _, required := range []string{
		"event: prelude", `"upload_bytes":1024`, `"seq":0`, `"seq":1`, `"seq":2`, "event: summary",
	} {
		if !strings.Contains(text, required) {
			t.Fatalf("missing %q in %s", required, text)
		}
	}
}

func TestTokenSimRejectsPayloadLengthMismatch(t *testing.T) {
	plan := validTokenSimPlan()
	body := tokenSimRequestBody(t, plan, 512)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/token-sim", bytes.NewReader(body))
	recorder := httptest.NewRecorder()
	(&app{}).routes().ServeHTTP(recorder, req)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), "content length does not match plan") {
		t.Fatalf("unexpected body: %s", recorder.Body.String())
	}
}

func TestTokenSimRejectsNetworkOutcomeFields(t *testing.T) {
	plan := validTokenSimPlan()
	encoded, err := json.Marshal(plan)
	if err != nil {
		t.Fatal(err)
	}
	encoded = bytes.TrimSuffix(encoded, []byte("}"))
	encoded = append(encoded, []byte(`,"packet_loss":0.1}`)...)
	var body bytes.Buffer
	_ = binary.Write(&body, binary.BigEndian, uint32(len(encoded)))
	body.Write(encoded)
	body.Write(bytes.Repeat([]byte{1}, int(plan.UploadPayloadBytes)))
	req := httptest.NewRequest(http.MethodPost, "/api/v1/token-sim", bytes.NewReader(body.Bytes()))
	recorder := httptest.NewRecorder()
	(&app{}).routes().ServeHTTP(recorder, req)
	if recorder.Code != http.StatusBadRequest || !strings.Contains(recorder.Body.String(), "unknown field") {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestTokenSimRejectsTrailingPlanData(t *testing.T) {
	plan := validTokenSimPlan()
	encoded, err := json.Marshal(plan)
	if err != nil {
		t.Fatal(err)
	}
	encoded = append(encoded, []byte(` {}`)...)
	var body bytes.Buffer
	_ = binary.Write(&body, binary.BigEndian, uint32(len(encoded)))
	body.Write(encoded)
	body.Write(bytes.Repeat([]byte{1}, int(plan.UploadPayloadBytes)))
	req := httptest.NewRequest(http.MethodPost, "/api/v1/token-sim", bytes.NewReader(body.Bytes()))
	recorder := httptest.NewRecorder()
	(&app{}).routes().ServeHTTP(recorder, req)
	if recorder.Code != http.StatusBadRequest || !strings.Contains(recorder.Body.String(), "trailing JSON data") {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestTokenSimRejectsUnknownWorkload(t *testing.T) {
	plan := validTokenSimPlan()
	plan.WorkloadKind = "synthetic-network-outcome"
	body := tokenSimRequestBody(t, plan, int(plan.UploadPayloadBytes))
	req := httptest.NewRequest(http.MethodPost, "/api/v1/token-sim", bytes.NewReader(body))
	recorder := httptest.NewRecorder()
	(&app{}).routes().ServeHTTP(recorder, req)
	if recorder.Code != http.StatusBadRequest || !strings.Contains(recorder.Body.String(), "unsupported workload_kind") {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestTokenSimAcceptsStressUploadAndRetainsHardLimit(t *testing.T) {
	plan := validTokenSimPlan()
	plan.WorkloadKind = "video"
	plan.UploadPayloadBytes = 100 << 20
	if err := validateTokenSimPlan(plan); err != nil {
		t.Fatalf("100MiB stress upload rejected: %v", err)
	}

	plan.UploadPayloadBytes = tokenSimMaxUploadBytes + 1
	if err := validateTokenSimPlan(plan); err == nil || !strings.Contains(err.Error(), "invalid upload_payload_bytes") {
		t.Fatalf("oversized upload was not rejected: %v", err)
	}
}
