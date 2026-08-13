package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

// contractFields 是一份满足上报合同的字段前缀（不含收尾 }，方便拼接）。
const contractFields = `"claim_scope":"application_end_to_end_to_probe_node",` +
	`"kpi_set":"agent-qoe-kpi-v0.1","aqs_version":"aqs-v0.1",` +
	`"profile_versions":"s1=0.2.0;s2=0.2.0;s3=0.2.0","schema_version":"1.0"`

type allowResultsTestAuthorizer struct{}

func (allowResultsTestAuthorizer) authorized(*http.Request) bool { return true }

func newResultsTestApp(dataDir string) *app {
	return &app{
		profiles:         map[string]*Profile{},
		dataDir:          dataDir,
		resultUploadAuth: allowResultsTestAuthorizer{},
	}
}

func postResultForTest(t *testing.T, endpoint string, body []byte) *http.Response {
	t.Helper()
	request, err := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/json")
	var document map[string]any
	if json.Unmarshal(body, &document) == nil {
		if runID := resultDocumentRunID(document); runID != "" {
			request.Header.Set("Idempotency-Key", runID)
		}
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	return response
}

func TestResultsAcceptsCurrentTokenV2EnvelopeExactlyOnce(t *testing.T) {
	dataDir := t.TempDir()
	a := newResultsTestApp(dataDir)
	body := currentTokenV2Envelope(t)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/results", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Idempotency-Key", resultDocumentRunID(mapFromJSON(t, body)))
	recorder := httptest.NewRecorder()
	a.handleResults(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("current Token v2 result: status %d, want 200 (body %q)", recorder.Code, recorder.Body.String())
	}

	path := filepath.Join(dataDir, "results", time.Now().Format("20060102")+".jsonl")
	persisted, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read persisted Token v2 result: %v", err)
	}
	lines := strings.Split(strings.TrimSuffix(string(persisted), "\n"), "\n")
	if len(lines) != 1 {
		t.Fatalf("persisted Token v2 lines = %d, want exactly 1", len(lines))
	}
	var submittedDoc any
	var persistedDoc any
	if err := json.Unmarshal(body, &submittedDoc); err != nil {
		t.Fatalf("decode submitted Token v2 fixture: %v", err)
	}
	if err := json.Unmarshal([]byte(lines[0]), &persistedDoc); err != nil {
		t.Fatalf("decode persisted Token v2 result: %v", err)
	}
	if !reflect.DeepEqual(submittedDoc, persistedDoc) {
		t.Fatal("persisted Token v2 result differs from the submitted document")
	}
}

func currentTokenV2Envelope(t *testing.T) []byte {
	t.Helper()
	fixturePath := filepath.Join(
		"..", "spec", "examples", "aneb-result-v1",
		"token_simulation.not-computable.valid-schema.json",
	)
	frozenV1, err := os.ReadFile(fixturePath)
	if err != nil {
		t.Fatalf("read frozen Token fixture: %v", err)
	}
	var doc map[string]any
	if err := json.Unmarshal(frozenV1, &doc); err != nil {
		t.Fatalf("decode frozen Token fixture: %v", err)
	}
	doc["schema_version"] = "aneb-result-v2"
	producer := doc["producer"].(map[string]any)
	producer["component"] = "aneb-probe-android"
	producer["exporter_version"] = "aneb-result-exporter-v2"
	run := doc["run"].(map[string]any)
	run["source_record_version"] = "room-v19-token-envelope-v1"
	category := doc["category_payload"].(map[string]any)
	rawEvidence := category["raw_evidence"].(map[string]any)
	rawEvidence["tasks"] = []any{map[string]any{
		"workload_kind":        "text",
		"upload_bytes":         float64(8192),
		"success":              true,
		"network_failure":      false,
		"task_id":              "quick-text-0",
		"server_processing_ms": float64(300),
		"ttft_ms":              float64(340),
		"request_count":        float64(1),
		"failed_request_count": float64(0),
	}}
	body, err := json.Marshal(doc)
	if err != nil {
		t.Fatalf("encode current Token v2 fixture: %v", err)
	}
	return body
}

func TestResultsAppendJsonl(t *testing.T) {
	dataDir := t.TempDir()
	a := newResultsTestApp(dataDir)
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	post := func(body string) *http.Response {
		resp := postResultForTest(t, srv.URL+"/api/v1/results", []byte(body))
		t.Cleanup(func() { resp.Body.Close() })
		return resp
	}

	if resp := post(`{"run_id":"r1","aqs":88,` + contractFields + `}`); resp.StatusCode != http.StatusOK {
		t.Fatalf("first post status %d", resp.StatusCode)
	}
	if resp := post("{\n  \"run_id\": \"r2\",\n  " + contractFields + "\n}"); resp.StatusCode != http.StatusOK {
		t.Fatalf("second post status %d", resp.StatusCode)
	}

	path := filepath.Join(dataDir, "results", time.Now().Format("20060102")+".jsonl")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read jsonl: %v", err)
	}
	lines := strings.Split(strings.TrimRight(string(data), "\n"), "\n")
	if len(lines) != 2 {
		t.Fatalf("got %d lines, want 2: %q", len(lines), string(data))
	}
	// 每行一个 compact JSON（多行输入被压成单行）。
	if lines[0] != `{"run_id":"r1","aqs":88,`+contractFields+`}` {
		t.Fatalf("line 0 = %q", lines[0])
	}
	if strings.Contains(lines[1], "\n") || !strings.Contains(lines[1], `"run_id":"r2"`) {
		t.Fatalf("line 1 = %q", lines[1])
	}
}

func TestResultsRejectsInvalid(t *testing.T) {
	a := newResultsTestApp(t.TempDir())
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	// 非 JSON。
	resp := postResultForTest(t, srv.URL+"/api/v1/results", []byte("not json"))
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("invalid JSON: status %d, want 400", resp.StatusCode)
	}

	// 超过 1MB。
	big := bytes.Repeat([]byte("a"), (1<<20)+100)
	resp = postResultForTest(t, srv.URL+"/api/v1/results", big)
	resp.Body.Close()
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized: status %d, want 413", resp.StatusCode)
	}
}

// 上报合同校验（设计文档 §6 最后一条）：合法通过 / 缺字段 400 并点名 /
// claim_scope 篡改 400 并点名。
func TestResultsContract(t *testing.T) {
	dataDir := t.TempDir()
	a := newResultsTestApp(dataDir)
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	post := func(body string) (int, string) {
		resp := postResultForTest(t, srv.URL+"/api/v1/results", []byte(body))
		defer resp.Body.Close()
		b, err := io.ReadAll(resp.Body)
		if err != nil {
			t.Fatal(err)
		}
		return resp.StatusCode, string(b)
	}

	// ① 合法上报通过并落盘。
	valid := `{"run_id":"ok-1",` + contractFields + `}`
	if code, body := post(valid); code != http.StatusOK {
		t.Fatalf("valid report: status %d, body %q", code, body)
	}

	// ② 缺字段：逐个删掉必填字段，都必须 400 且错误信息点名该字段。
	for _, field := range []string{"claim_scope", "kpi_set", "aqs_version", "profile_versions", "schema_version"} {
		var doc map[string]any
		if err := json.Unmarshal([]byte(valid), &doc); err != nil {
			t.Fatal(err)
		}
		delete(doc, field)
		b, err := json.Marshal(doc)
		if err != nil {
			t.Fatal(err)
		}
		code, body := post(string(b))
		if code != http.StatusBadRequest {
			t.Fatalf("missing %s: status %d, want 400 (body %q)", field, code, body)
		}
		if !strings.Contains(body, field) {
			t.Fatalf("missing %s: error body does not name the field: %q", field, body)
		}
	}

	// ③ claim_scope 篡改（越权口径声明，R-30）必须 400 且点名。
	tampered := strings.Replace(valid,
		"application_end_to_end_to_probe_node", "radio_layer_root_cause", 1)
	code, body := post(tampered)
	if code != http.StatusBadRequest {
		t.Fatalf("tampered claim_scope: status %d, want 400 (body %q)", code, body)
	}
	if !strings.Contains(body, "claim_scope") {
		t.Fatalf("tampered claim_scope: error body does not name the field: %q", body)
	}

	// ④ 版本字段空串 / schema_version 不在枚举内：同样 400。
	if code, body := post(strings.Replace(valid, `"kpi_set":"agent-qoe-kpi-v0.1"`, `"kpi_set":""`, 1)); code != http.StatusBadRequest || !strings.Contains(body, "kpi_set") {
		t.Fatalf("empty kpi_set: status %d body %q", code, body)
	}
	if code, body := post(strings.Replace(valid, `"schema_version":"1.0"`, `"schema_version":"2.0"`, 1)); code != http.StatusBadRequest || !strings.Contains(body, "schema_version") {
		t.Fatalf("schema_version 2.0: status %d body %q", code, body)
	}

	// 被拒上报绝不落盘：jsonl 里只有 ① 的一行。
	data, err := os.ReadFile(filepath.Join(dataDir, "results", time.Now().Format("20060102")+".jsonl"))
	if err != nil {
		t.Fatalf("read jsonl: %v", err)
	}
	if got := strings.Count(string(data), "\n"); got != 1 {
		t.Fatalf("jsonl has %d lines, want 1 (rejected reports must not be persisted)", got)
	}
}

// resultsMu 并发正确性：10 goroutine 同时 POST 不同 JSON，落盘必须恰好
// 10 行、每行可独立 json.Unmarshal（无交织/截断）、run_id 各出现一次。
func TestResultsConcurrent(t *testing.T) {
	dataDir := t.TempDir()
	a := newResultsTestApp(dataDir)
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	const workers = 10
	// pad 拉长行体，提升写交织（若锁失效）被撞出来的概率。
	pad := strings.Repeat("x", 512)
	errs := make([]error, workers)
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			body := fmt.Sprintf(`{"run_id":"concurrent-%d","idx":%d,"pad":%q,`+contractFields+`}`, i, i, pad)
			request, err := http.NewRequest(
				http.MethodPost,
				srv.URL+"/api/v1/results",
				strings.NewReader(body),
			)
			if err != nil {
				errs[i] = err
				return
			}
			request.Header.Set("Content-Type", "application/json")
			request.Header.Set("Idempotency-Key", fmt.Sprintf("concurrent-%d", i))
			resp, err := http.DefaultClient.Do(request)
			if err != nil {
				errs[i] = err
				return
			}
			resp.Body.Close()
			if resp.StatusCode != http.StatusOK {
				errs[i] = fmt.Errorf("status %d", resp.StatusCode)
			}
		}(i)
	}
	wg.Wait()
	for i, err := range errs {
		if err != nil {
			t.Fatalf("worker %d: %v", i, err)
		}
	}

	path := filepath.Join(dataDir, "results", time.Now().Format("20060102")+".jsonl")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read jsonl: %v", err)
	}
	lines := strings.Split(strings.TrimRight(string(data), "\n"), "\n")
	if len(lines) != workers {
		t.Fatalf("got %d lines, want %d", len(lines), workers)
	}
	seen := make(map[string]bool, workers)
	for i, ln := range lines {
		var obj struct {
			RunID string `json:"run_id"`
			Idx   int    `json:"idx"`
			Pad   string `json:"pad"`
		}
		if err := json.Unmarshal([]byte(ln), &obj); err != nil {
			t.Fatalf("line %d not independently parseable JSON: %v (%q)", i, err, ln)
		}
		if obj.RunID == "" || seen[obj.RunID] {
			t.Fatalf("line %d run_id %q missing or duplicated", i, obj.RunID)
		}
		if obj.Pad != pad {
			t.Fatalf("line %d pad corrupted (interleaved write?)", i)
		}
		seen[obj.RunID] = true
	}
}

func mapFromJSON(t *testing.T, body []byte) map[string]any {
	t.Helper()
	var document map[string]any
	if err := json.Unmarshal(body, &document); err != nil {
		t.Fatal(err)
	}
	return document
}
