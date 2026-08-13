package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type countingResultBody struct {
	reader    *bytes.Reader
	readCalls int
	readBytes int
}

func newCountingResultBody(payload []byte) *countingResultBody {
	return &countingResultBody{reader: bytes.NewReader(payload)}
}

func (body *countingResultBody) Read(target []byte) (int, error) {
	body.readCalls++
	count, err := body.reader.Read(target)
	body.readBytes += count
	return count, err
}

func TestDisabledResultUploadRejectsBeforeReadingBodyOrTouchingStorage(t *testing.T) {
	dataDir := t.TempDir()
	app := &app{
		profiles: map[string]*Profile{},
		dataDir:  dataDir,
	}
	payload := []byte(`{"hostile":"RESULT_BODY_MUST_NOT_BE_READ"}`)
	body := newCountingResultBody(payload)
	request := httptest.NewRequest(http.MethodPost, "/api/v1/results", body)
	request.ContentLength = int64(len(payload))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Expect", "100-continue")
	recorder := httptest.NewRecorder()

	app.handleResults(recorder, request)

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("disabled result upload status = %d, want 503", recorder.Code)
	}
	if recorder.Body.String() != "result upload unavailable\n" {
		t.Fatalf("disabled result upload body = %q", recorder.Body.String())
	}
	if body.readCalls != 0 || body.readBytes != 0 {
		t.Fatalf("disabled result upload read calls/bytes = %d/%d, want 0/0", body.readCalls, body.readBytes)
	}
	assertResultsStorageAbsent(t, dataDir)
}

func TestResultsRequireConfiguredBearerBeforeStorage(t *testing.T) {
	const token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	dataDir := t.TempDir()
	authorizer, err := newResultUploadAuthorizer(token)
	if err != nil {
		t.Fatal(err)
	}
	a := &app{
		profiles:         map[string]*Profile{},
		dataDir:          dataDir,
		resultUploadAuth: authorizer,
	}
	server := httptest.NewServer(a.routes())
	defer server.Close()
	body := currentTokenV2Envelope(t)

	post := func(authorization string) int {
		request, err := http.NewRequest(
			http.MethodPost,
			server.URL+"/api/v1/results",
			bytes.NewReader(body),
		)
		if err != nil {
			t.Fatal(err)
		}
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("Idempotency-Key", resultDocumentRunID(mapFromJSON(t, body)))
		if authorization != "" {
			request.Header.Set("Authorization", authorization)
		}
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		return response.StatusCode
	}

	if status := post(""); status != http.StatusUnauthorized {
		t.Fatalf("missing bearer status = %d, want 401", status)
	}
	if status := post("Bearer " + strings.Repeat("f", 64)); status != http.StatusUnauthorized {
		t.Fatalf("wrong bearer status = %d, want 401", status)
	}
	assertResultsStorageAbsent(t, dataDir)

	if status := post("Bearer " + token); status != http.StatusOK {
		t.Fatalf("configured bearer status = %d, want 200", status)
	}
	entries, err := os.ReadDir(filepath.Join(dataDir, "results"))
	if err != nil {
		t.Fatalf("read results directory: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("results files = %d, want 1", len(entries))
	}
	persisted, err := os.ReadFile(filepath.Join(dataDir, "results", entries[0].Name()))
	if err != nil {
		t.Fatalf("read persisted result: %v", err)
	}
	if lines := strings.Count(string(persisted), "\n"); lines != 1 {
		t.Fatalf("persisted lines = %d, want 1", lines)
	}
	if strings.Contains(string(persisted), token) {
		t.Fatal("persisted result contains bearer token")
	}
}

func TestResultUploadAuthorizerLoadsOnlyValidTokenFile(t *testing.T) {
	const token = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
	path := filepath.Join(t.TempDir(), "result-upload-token")
	if err := os.WriteFile(path, []byte(token+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	authorizer, err := loadResultUploadAuthorizer(path)
	if err != nil {
		t.Fatalf("load valid result upload token: %v", err)
	}
	request := httptest.NewRequest(http.MethodPost, "/api/v1/results", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	if !authorizer.authorized(request) {
		t.Fatal("loaded authorizer rejected the configured bearer")
	}
	request.Header.Set("Authorization", "Bearer "+strings.Repeat("0", 64))
	if authorizer.authorized(request) {
		t.Fatal("loaded authorizer accepted a different bearer")
	}

	if err := os.WriteFile(path, []byte("not-a-token\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadResultUploadAuthorizer(path); err == nil {
		t.Fatal("invalid result upload token file was accepted")
	}
}

func TestResultsIdempotencyKeyPreventsDuplicateAppend(t *testing.T) {
	const token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	dataDir := t.TempDir()
	authorizer, err := newResultUploadAuthorizer(token)
	if err != nil {
		t.Fatal(err)
	}
	a := &app{
		profiles:         map[string]*Profile{},
		dataDir:          dataDir,
		resultUploadAuth: authorizer,
	}
	server := httptest.NewServer(a.routes())
	defer server.Close()
	body := currentTokenV2Envelope(t)
	var document map[string]any
	if err := json.Unmarshal(body, &document); err != nil {
		t.Fatal(err)
	}
	run, ok := document["run"].(map[string]any)
	if !ok {
		t.Fatal("fixture run object is absent")
	}
	runID, ok := run["run_id"].(string)
	if !ok || runID == "" {
		t.Fatal("fixture run_id is absent")
	}

	post := func(payload []byte) int {
		request, err := http.NewRequest(
			http.MethodPost,
			server.URL+"/api/v1/results",
			bytes.NewReader(payload),
		)
		if err != nil {
			t.Fatal(err)
		}
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("Authorization", "Bearer "+token)
		request.Header.Set("Idempotency-Key", runID)
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		return response.StatusCode
	}

	if status := post(body); status != http.StatusOK {
		t.Fatalf("first submission status = %d, want 200", status)
	}
	if status := post(body); status != http.StatusOK {
		t.Fatalf("same-body replay status = %d, want 200", status)
	}
	producer, ok := document["producer"].(map[string]any)
	if !ok {
		t.Fatal("fixture producer object is absent")
	}
	producer["serialized_at_epoch_ms"] = float64(1_700_000_000_999)
	conflictingBody, err := json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	if status := post(conflictingBody); status != http.StatusConflict {
		t.Fatalf("same-key different-body status = %d, want 409", status)
	}

	entries, err := os.ReadDir(filepath.Join(dataDir, "results"))
	if err != nil {
		t.Fatal(err)
	}
	persisted, err := os.ReadFile(filepath.Join(dataDir, "results", entries[0].Name()))
	if err != nil {
		t.Fatal(err)
	}
	if lines := strings.Count(string(persisted), "\n"); lines != 1 {
		t.Fatalf("persisted lines = %d, want 1", lines)
	}
}

func TestResultsIdempotencySurvivesDailyFileRollover(t *testing.T) {
	const token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	dataDir := t.TempDir()
	authorizer, err := newResultUploadAuthorizer(token)
	if err != nil {
		t.Fatal(err)
	}
	a := &app{
		profiles:         map[string]*Profile{},
		dataDir:          dataDir,
		resultUploadAuth: authorizer,
	}
	server := httptest.NewServer(a.routes())
	defer server.Close()
	body := currentTokenV2Envelope(t)
	document := mapFromJSON(t, body)
	runID := resultDocumentRunID(document)
	var compact bytes.Buffer
	if err := json.Compact(&compact, body); err != nil {
		t.Fatal(err)
	}
	resultsDir := filepath.Join(dataDir, "results")
	if err := os.MkdirAll(resultsDir, 0o700); err != nil {
		t.Fatal(err)
	}
	previousPath := filepath.Join(resultsDir, "20000101.jsonl")
	if err := os.WriteFile(previousPath, append(compact.Bytes(), '\n'), 0o600); err != nil {
		t.Fatal(err)
	}

	post := func(payload []byte) int {
		request, err := http.NewRequest(
			http.MethodPost,
			server.URL+"/api/v1/results",
			bytes.NewReader(payload),
		)
		if err != nil {
			t.Fatal(err)
		}
		request.Header.Set("Authorization", "Bearer "+token)
		request.Header.Set("Idempotency-Key", runID)
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		return response.StatusCode
	}

	if status := post(body); status != http.StatusOK {
		t.Fatalf("cross-day same-body replay status = %d, want 200", status)
	}
	producer := document["producer"].(map[string]any)
	producer["serialized_at_epoch_ms"] = float64(1_700_000_000_777)
	conflictingBody, err := json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	if status := post(conflictingBody); status != http.StatusConflict {
		t.Fatalf("cross-day same-key different-body status = %d, want 409", status)
	}

	entries, err := os.ReadDir(resultsDir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != "20000101.jsonl" {
		t.Fatalf("result files after cross-day replay = %v, want only 20000101.jsonl", entries)
	}
	persisted, err := os.ReadFile(previousPath)
	if err != nil {
		t.Fatal(err)
	}
	if lines := strings.Count(string(persisted), "\n"); lines != 1 {
		t.Fatalf("persisted lines = %d, want 1", lines)
	}
}

func TestResultsConflictingCrossDayIdentityDominatesDuplicate(t *testing.T) {
	const token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	dataDir := t.TempDir()
	authorizer, err := newResultUploadAuthorizer(token)
	if err != nil {
		t.Fatal(err)
	}
	a := &app{
		profiles:         map[string]*Profile{},
		dataDir:          dataDir,
		resultUploadAuth: authorizer,
	}
	server := httptest.NewServer(a.routes())
	defer server.Close()

	body := currentTokenV2Envelope(t)
	document := mapFromJSON(t, body)
	runID := resultDocumentRunID(document)
	var compact bytes.Buffer
	if err := json.Compact(&compact, body); err != nil {
		t.Fatal(err)
	}
	duplicateLine := append(append([]byte(nil), compact.Bytes()...), '\n')
	producer := document["producer"].(map[string]any)
	producer["serialized_at_epoch_ms"] = float64(1_700_000_000_555)
	conflictingBody, err := json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	var conflictingCompact bytes.Buffer
	if err := json.Compact(&conflictingCompact, conflictingBody); err != nil {
		t.Fatal(err)
	}
	conflictingLine := append(append([]byte(nil), conflictingCompact.Bytes()...), '\n')

	resultsDir := filepath.Join(dataDir, "results")
	if err := os.MkdirAll(resultsDir, 0o700); err != nil {
		t.Fatal(err)
	}
	duplicatePath := filepath.Join(resultsDir, "20000101.jsonl")
	conflictPath := filepath.Join(resultsDir, "20000102.jsonl")
	if err := os.WriteFile(duplicatePath, duplicateLine, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(conflictPath, conflictingLine, 0o600); err != nil {
		t.Fatal(err)
	}

	request, err := http.NewRequest(
		http.MethodPost,
		server.URL+"/api/v1/results",
		bytes.NewReader(body),
	)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Idempotency-Key", runID)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusConflict {
		t.Fatalf("duplicate plus conflict status = %d, want 409", response.StatusCode)
	}
	assertFileBytesEqual(t, duplicatePath, duplicateLine)
	assertFileBytesEqual(t, conflictPath, conflictingLine)
	entries, err := os.ReadDir(resultsDir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 2 {
		t.Fatalf("result files = %d, want 2", len(entries))
	}
}

func TestResultsRejectNonCanonicalV2RunIDBeforeStorage(t *testing.T) {
	const token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	dataDir := t.TempDir()
	authorizer, err := newResultUploadAuthorizer(token)
	if err != nil {
		t.Fatal(err)
	}
	a := &app{
		profiles:         map[string]*Profile{},
		dataDir:          dataDir,
		resultUploadAuth: authorizer,
	}
	body := currentTokenV2Envelope(t)
	document := mapFromJSON(t, body)
	run := document["run"].(map[string]any)
	run["run_id"] = "not-a-canonical-run-id"
	body, err = json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/api/v1/results", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+token)
	request.Header.Set("Idempotency-Key", "not-a-canonical-run-id")
	recorder := httptest.NewRecorder()

	a.handleResults(recorder, request)

	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("non-canonical v2 run id status = %d, want 400", recorder.Code)
	}
	assertResultsStorageAbsent(t, dataDir)
}

func assertResultsStorageAbsent(t *testing.T, dataDir string) {
	t.Helper()
	_, err := os.Stat(filepath.Join(dataDir, "results"))
	if err == nil {
		t.Fatal("unauthorized request created results storage")
	}
	if !os.IsNotExist(err) {
		t.Fatalf("stat results directory: %v", err)
	}
}

func assertFileBytesEqual(t *testing.T, path string, expected []byte) {
	t.Helper()
	actual, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(actual, expected) {
		t.Fatalf("persisted bytes changed for %s", filepath.Base(path))
	}
}
