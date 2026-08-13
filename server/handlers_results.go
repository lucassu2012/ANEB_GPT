package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const resultsMaxBytes = 1 << 20 // 1MB

// 结果上报合同（设计文档 §6 最后一条，R-30 claim scope 泄漏防线）：
//   - claim_scope 必须逐字节等于 claimScopeConst——App 层测量只允许声明
//     "应用层端到端到探针节点"口径，任何别的声明一律拒收；
//   - kpi_set / aqs_version / profile_versions / schema_version 必填非空字符串；
//   - schema_version 当前只接受 "1.0"（升版需扩 acceptedSchemaVersions）。
//
// 校验手写（不引第三方 JSON Schema 库，供应链纪律见设计文档 §8 阶段 0）。
//
// R-27 预留（本轮不实现聚合）：ITL 直方图分桶方案属于版本化合同的一部分，
// 桶界集合 = 对数网格 ∪ {100, 200, 400, 1000ms}（T2/T3/T4 全部门限锚点），
// 保证服务端复算 stall 率与本地精确值一致；届时在此处追加 itl_histogram
// 桶界版本号校验，schema_version 升版。
const claimScopeConst = "application_end_to_end_to_probe_node"

var acceptedLegacySchemaVersions = map[string]bool{"1.0": true}
var canonicalResultV2RunID = regexp.MustCompile(
	`^[0-9a-f]{8}-[0-9a-f]{4}-[47][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`,
)

// validateResultContract 返回不合规字段的错误描述列表（空 = 合规）。
// 每条错误以字段名开头，客户端与人工排障都能直接定位。
func validateResultContract(doc map[string]any) []string {
	if schemaVersion, _ := doc["schema_version"].(string); schemaVersion == "aneb-result-v2" {
		return validateResultV2Contract(doc)
	}
	return validateLegacyResultContract(doc)
}

func validateLegacyResultContract(doc map[string]any) []string {
	var errs []string

	// claim_scope：const 锁定。
	if v, ok := doc["claim_scope"]; !ok {
		errs = append(errs, "claim_scope: missing (must be \""+claimScopeConst+"\")")
	} else if s, ok := v.(string); !ok || s != claimScopeConst {
		errs = append(errs, "claim_scope: invalid (must be \""+claimScopeConst+"\")")
	}

	// 版本字段：必填非空字符串。
	for _, field := range []string{"kpi_set", "aqs_version", "profile_versions", "schema_version"} {
		v, ok := doc[field]
		if !ok {
			errs = append(errs, field+": missing (non-empty string required)")
			continue
		}
		s, ok := v.(string)
		if !ok || s == "" {
			errs = append(errs, field+": invalid (non-empty string required)")
		}
	}

	// schema_version 枚举锁定（仅当它是非空字符串时才进一步查枚举，
	// 避免同一字段报两条错）。
	if s, ok := doc["schema_version"].(string); ok && s != "" && !acceptedLegacySchemaVersions[s] {
		errs = append(errs, "schema_version: unsupported \""+s+"\" (accepted: \"1.0\")")
	}
	return errs
}

func validateResultV2Contract(doc map[string]any) []string {
	var errs []string
	requiredObjects := []string{
		"producer", "completeness", "result_semantics", "run", "profile", "claim",
		"context", "evaluation", "evidence", "category_payload",
	}
	allowedTopLevel := map[string]bool{
		"schema_version": true, "test_type": true, "producer": true,
		"completeness": true, "result_semantics": true, "run": true,
		"profile": true, "claim": true, "context": true, "evaluation": true,
		"evidence": true, "category_payload": true,
	}
	for field := range doc {
		if !allowedTopLevel[field] {
			errs = append(errs, field+": unsupported top-level field")
		}
	}
	for _, field := range requiredObjects {
		if _, ok := doc[field].(map[string]any); !ok {
			errs = append(errs, field+": missing or invalid object")
		}
	}

	testType, ok := doc["test_type"].(string)
	if !ok || testType == "" {
		errs = append(errs, "test_type: missing or invalid")
	} else if _, known := resultV2ClaimScopes[testType]; !known {
		errs = append(errs, "test_type: unsupported")
	}
	if run, ok := doc["run"].(map[string]any); ok {
		if runID, ok := run["run_id"].(string); !ok || !canonicalResultV2RunID.MatchString(runID) {
			errs = append(errs, "run.run_id: missing or invalid")
		}
	}
	if claim, ok := doc["claim"].(map[string]any); ok {
		scope, scopeOK := claim["scope"].(string)
		allowedScopes := resultV2ClaimScopes[testType]
		if !scopeOK || !allowedScopes[scope] {
			errs = append(errs, "claim.scope: invalid for test_type")
		}
	}
	if testType == "token_simulation" {
		errs = append(errs, validateTokenV2TaskAlignment(doc)...)
	}
	return errs
}

var resultV2ClaimScopes = map[string]map[string]bool{
	"token_simulation": {
		"application_end_to_end_to_probe_node": true,
	},
	"ai_realtime_simulation": {
		"application_end_to_end_to_probe_node":                true,
		"controlled_server_disconnect_recovery_to_probe_node": true,
	},
	"network_comprehensive": {
		"application_end_to_end_to_probe_node":                                          true,
		"application_end_to_end_to_probe_node_through_dedicated_ip_forwarding_gateway":  true,
		"application_end_to_end_to_probe_node_through_dedicated_gateway_with_ip_outage": true,
	},
}

func validateTokenV2TaskAlignment(doc map[string]any) []string {
	category, ok := doc["category_payload"].(map[string]any)
	if !ok {
		return nil
	}
	rawEvidence, ok := category["raw_evidence"].(map[string]any)
	if !ok {
		return []string{"category_payload.raw_evidence: missing or invalid object"}
	}
	tasks, ok := rawEvidence["tasks"].([]any)
	if !ok {
		return []string{"category_payload.raw_evidence.tasks: missing or invalid array"}
	}
	var errs []string
	for _, value := range tasks {
		task, ok := value.(map[string]any)
		if !ok {
			errs = append(errs, "category_payload.raw_evidence.tasks: invalid item")
			continue
		}
		if taskID, ok := task["task_id"].(string); !ok || taskID == "" {
			errs = append(errs, "category_payload.raw_evidence.tasks.task_id: missing or invalid")
		}
		for _, field := range []string{"server_processing_ms", "ttft_ms"} {
			if _, ok := task[field]; !ok {
				errs = append(errs, "category_payload.raw_evidence.tasks: "+field+" missing")
			}
		}
	}
	return errs
}

// handleResults POST /api/v1/results：合同校验通过后追加写
// <dataDir>/results/YYYYMMDD.jsonl，每行一个 JSON（compact）。
// 文件名日期用墙钟——这是日志归档命名，不是逐事件时间戳，不违反 R-24。
func (a *app) handleResults(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if a.resultUploadAuth == nil {
		http.Error(w, "result upload unavailable", http.StatusServiceUnavailable)
		return
	}
	if !a.resultUploadAuth.authorized(r) {
		w.Header().Set("WWW-Authenticate", "Bearer")
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, resultsMaxBytes))
	if err != nil {
		http.Error(w, "body too large or unreadable", http.StatusRequestEntityTooLarge)
		return
	}
	if len(body) == 0 || !json.Valid(body) {
		http.Error(w, "body must be a single JSON document", http.StatusBadRequest)
		return
	}
	var doc map[string]any
	if err := json.Unmarshal(body, &doc); err != nil {
		http.Error(w, "body must be a JSON object", http.StatusBadRequest)
		return
	}
	if contractErrs := validateResultContract(doc); len(contractErrs) > 0 {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadRequest)
		resp, _ := json.Marshal(map[string]any{"ok": false, "errors": contractErrs})
		_, _ = w.Write(resp)
		return
	}
	runID := resultDocumentRunID(doc)
	if idempotencyKey := r.Header.Get("Idempotency-Key"); idempotencyKey == "" || idempotencyKey != runID {
		http.Error(w, "idempotency key must equal run id", http.StatusBadRequest)
		return
	}
	var line bytes.Buffer
	line.Grow(len(body) + 1)
	if err := json.Compact(&line, body); err != nil {
		http.Error(w, "invalid JSON", http.StatusBadRequest)
		return
	}
	compactBody := append([]byte(nil), line.Bytes()...)
	line.WriteByte('\n')

	dir := filepath.Join(a.dataDir, "results")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	path := filepath.Join(dir, time.Now().Format("20060102")+".jsonl")

	a.resultsMu.Lock()
	defer a.resultsMu.Unlock()
	duplicate, conflict, err := inspectPersistedResults(dir, runID, compactBody)
	if err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	if conflict {
		http.Error(w, "idempotency conflict", http.StatusConflict)
		return
	}
	if duplicate {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true,"duplicate":true}`))
		return
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	if err := f.Chmod(0o600); err != nil {
		_ = f.Close()
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	if _, err := f.Write(line.Bytes()); err != nil {
		_ = f.Close()
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	if err := f.Close(); err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"ok":true}`))
}

func resultDocumentRunID(document map[string]any) string {
	if run, ok := document["run"].(map[string]any); ok {
		if runID, ok := run["run_id"].(string); ok {
			return runID
		}
	}
	runID, _ := document["run_id"].(string)
	return runID
}

func inspectPersistedResult(path, runID string, compactBody []byte) (duplicate, conflict bool, err error) {
	file, err := os.Open(path)
	if os.IsNotExist(err) {
		return false, false, nil
	}
	if err != nil {
		return false, false, err
	}
	wantedHash := sha256.Sum256(compactBody)
	reader := bufio.NewReader(file)
	foundDuplicate := false
	for {
		persistedLine, readErr := reader.ReadBytes('\n')
		persistedLine = bytes.TrimSuffix(persistedLine, []byte{'\n'})
		if len(persistedLine) > 0 {
			var persistedDocument map[string]any
			if json.Unmarshal(persistedLine, &persistedDocument) == nil &&
				resultDocumentRunID(persistedDocument) == runID {
				persistedHash := sha256.Sum256(persistedLine)
				if persistedHash == wantedHash {
					foundDuplicate = true
				} else {
					if err := file.Close(); err != nil {
						return false, false, err
					}
					return false, true, nil
				}
			}
		}
		if readErr == io.EOF {
			if err := file.Close(); err != nil {
				return false, false, err
			}
			return foundDuplicate, false, nil
		}
		if readErr != nil {
			_ = file.Close()
			return false, false, readErr
		}
	}
}

func inspectPersistedResults(dir, runID string, compactBody []byte) (duplicate, conflict bool, err error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return false, false, err
	}
	duplicatePath := ""
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".jsonl") {
			continue
		}
		path := filepath.Join(dir, entry.Name())
		foundDuplicate, foundConflict, err := inspectPersistedResult(
			path,
			runID,
			compactBody,
		)
		if err != nil || foundConflict {
			return false, foundConflict, err
		}
		if foundDuplicate && duplicatePath == "" {
			duplicatePath = path
		}
	}
	if duplicatePath != "" {
		if err := syncResultFile(duplicatePath); err != nil {
			return false, false, err
		}
		return true, false, nil
	}
	return false, false, nil
}

func syncResultFile(path string) error {
	file, err := os.OpenFile(path, os.O_RDWR, 0)
	if err != nil {
		return err
	}
	if err := file.Chmod(0o600); err != nil {
		_ = file.Close()
		return err
	}
	if err := file.Sync(); err != nil {
		_ = file.Close()
		return err
	}
	return file.Close()
}
