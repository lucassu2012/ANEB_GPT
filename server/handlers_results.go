package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
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

var acceptedSchemaVersions = map[string]bool{"1.0": true}

// validateResultContract 返回不合规字段的错误描述列表（空 = 合规）。
// 每条错误以字段名开头，客户端与人工排障都能直接定位。
func validateResultContract(doc map[string]any) []string {
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
	if s, ok := doc["schema_version"].(string); ok && s != "" && !acceptedSchemaVersions[s] {
		errs = append(errs, "schema_version: unsupported \""+s+"\" (accepted: \"1.0\")")
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
	var line bytes.Buffer
	line.Grow(len(body) + 1)
	if err := json.Compact(&line, body); err != nil {
		http.Error(w, "invalid JSON", http.StatusBadRequest)
		return
	}
	line.WriteByte('\n')

	dir := filepath.Join(a.dataDir, "results")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	path := filepath.Join(dir, time.Now().Format("20060102")+".jsonl")

	a.resultsMu.Lock()
	defer a.resultsMu.Unlock()
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	defer f.Close()
	if _, err := f.Write(line.Bytes()); err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"ok":true}`))
}
