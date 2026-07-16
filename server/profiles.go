package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// TokenBytes 描述单 token 事件 payload 字节数的对数正态分布参数。
type TokenBytes struct {
	Dist   string  `json:"dist"`
	Median float64 `json:"median"`
	Sigma  float64 `json:"sigma"`
}

// Burst 描述突发簇节奏（S2 编码 Agent 流）。
type Burst struct {
	ClusterTps   float64 `json:"cluster_tps"`
	PauseMs      []int   `json:"pause_ms"` // [min, max]
	ClusterGeomP float64 `json:"cluster_geom_p"`
}

// ProfilePresentation 是版本化展示合同。结论公式仍由客户端 policy id 对应实现控制。
type ProfilePresentation struct {
	LiveMetricID       string   `json:"live_metric_id,omitempty"`
	LiveMetricLabel    string   `json:"live_metric_label,omitempty"`
	LiveMetricUnit     string   `json:"live_metric_unit,omitempty"`
	LiveWindowMs       int      `json:"live_window_ms,omitempty"`
	UIRefreshMs        int      `json:"ui_refresh_ms,omitempty"`
	MetricIDs          []string `json:"metric_ids,omitempty"`
	ConclusionPolicyID string   `json:"conclusion_policy_id,omitempty"`
}

// Phase 是 profile 中一个阶段的联合体，字段按 type 选用。
type Phase struct {
	Type string `json:"type"`

	// clock_sync
	Samples int `json:"samples,omitempty"`

	// upload_burst
	Bytes    int64 `json:"bytes,omitempty"`
	ChunkKB  int   `json:"chunk_kb,omitempty"`
	Parallel int   `json:"parallel,omitempty"`

	// think_pause
	DurationMs int `json:"duration_ms,omitempty"`

	// token_stream
	Tokens     int         `json:"tokens,omitempty"`
	RateTps    float64     `json:"rate_tps,omitempty"`
	TokenBytes *TokenBytes `json:"token_bytes,omitempty"`
	Burst      *Burst      `json:"burst,omitempty"`
	Seed       int64       `json:"seed,omitempty"`

	// tool_loop
	Rounds       int   `json:"rounds,omitempty"`
	UpBytes      int64 `json:"up_bytes,omitempty"`
	DownBytes    int64 `json:"down_bytes,omitempty"`
	ServerProcMs int   `json:"server_proc_ms,omitempty"`
}

// Profile 是版本化场景定义（发布即冻结，修改必须升版本号）。
type Profile struct {
	ProfileID    string              `json:"profile_id"`
	Version      string              `json:"version"`
	ModeID       string              `json:"mode_id,omitempty"`
	KpiSet       string              `json:"kpi_set"`
	Description  string              `json:"description,omitempty"`
	EstDurationS float64             `json:"est_duration_s,omitempty"`
	Presentation ProfilePresentation `json:"presentation,omitempty"`
	Phases       []Phase             `json:"phases"`
}

// firstTokenStream 返回第 idx 个 token_stream phase（idx 从 0 计，只数 token_stream）。
func (p *Profile) tokenStreamPhase(idx int) (*Phase, error) {
	n := 0
	for i := range p.Phases {
		if p.Phases[i].Type == "token_stream" {
			if n == idx {
				return &p.Phases[i], nil
			}
			n++
		}
	}
	return nil, fmt.Errorf("profile %s: token_stream phase index %d not found (has %d)", p.ProfileID, idx, n)
}

// loadProfiles 读取目录下全部 *.json 并解析为 Profile。
// 解析失败任一文件即整体报错（profile 是两端共享合同，不允许静默跳过）。
func loadProfiles(dir string) (map[string]*Profile, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, fmt.Errorf("read profiles dir %s: %w", dir, err)
	}
	profiles := make(map[string]*Profile)
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(strings.ToLower(e.Name()), ".json") {
			continue
		}
		path := filepath.Join(dir, e.Name())
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, fmt.Errorf("read %s: %w", path, err)
		}
		var p Profile
		if err := json.Unmarshal(data, &p); err != nil {
			return nil, fmt.Errorf("parse %s: %w", path, err)
		}
		if p.ProfileID == "" || p.Version == "" {
			return nil, fmt.Errorf("parse %s: missing profile_id or version", path)
		}
		if _, dup := profiles[p.ProfileID]; dup {
			return nil, fmt.Errorf("duplicate profile_id %q in %s", p.ProfileID, path)
		}
		profiles[p.ProfileID] = &p
	}
	return profiles, nil
}

// handleProfiles GET /api/v1/profiles：下发全部 profile（含 profile_id/version）。
func (a *app) handleProfiles(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	ids := make([]string, 0, len(a.profiles))
	for id := range a.profiles {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	list := make([]*Profile, 0, len(ids))
	for _, id := range ids {
		list = append(list, a.profiles[id])
	}
	w.Header().Set("Content-Type", "application/json")
	enc := json.NewEncoder(w)
	_ = enc.Encode(map[string]any{
		"server_version": serverVersion,
		"profiles":       list,
	})
}
