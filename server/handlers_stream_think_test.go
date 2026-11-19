package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

type summaryThinkData struct {
	Tokens        int     `json:"tokens"`
	ThinkSeqs     []int64 `json:"think_seqs"`
	ThinkDwellsUs []int64 `json:"think_dwells_us"`
	SchedRef      int64   `json:"stream_start_us"`
}

// profile 声明 think_injections：summary 透出 think_seqs/think_dwells_us；对应 token sched 右移。
func TestStreamThinkInjectionFromProfile(t *testing.T) {
	prof := &Profile{
		ProfileID: "think_prof", Version: "t@1",
		Phases: []Phase{{
			Type: "token_stream", Tokens: 8, RateTps: 5000, Seed: 1,
			ThinkInjections: []ThinkInjection{{AtSeq: 4, DwellUs: 20_000}},
		}},
	}
	a := &app{profiles: map[string]*Profile{"think_prof": prof}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	frames := fetchStream(t, srv.URL+"/api/v1/stream?profile=think_prof")
	var sum summaryThinkData
	if err := json.Unmarshal([]byte(frames[len(frames)-1].data), &sum); err != nil {
		t.Fatalf("summary JSON: %v", err)
	}
	if len(sum.ThinkSeqs) != 1 || sum.ThinkSeqs[0] != 4 {
		t.Fatalf("think_seqs = %v, want [4]", sum.ThinkSeqs)
	}
	if len(sum.ThinkDwellsUs) != 1 || sum.ThinkDwellsUs[0] != 20_000 {
		t.Fatalf("think_dwells_us = %v, want [20000]", sum.ThinkDwellsUs)
	}

	// seq4 相对 seq3 的 sched 跨度应比常规间隔多 ~20ms。
	var t3, t4 tokenData
	_ = json.Unmarshal([]byte(frames[1+3].data), &t3)
	_ = json.Unmarshal([]byte(frames[1+4].data), &t4)
	nominal := int64(1_000_000 / 5000) // 200us
	if gap := t4.SchedUs - t3.SchedUs; gap != nominal+20_000 {
		t.Fatalf("think gap seq3→4 = %d, want %d", gap, nominal+20_000)
	}
}

// 默认（无 think）：summary 不含 think 字段（默认字节级不变）。
func TestStreamThinkInjectionDefaultAbsent(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()
	frames := fetchStream(t, srv.URL+"/api/v1/stream?tokens=5&rate_tps=5000")
	var raw map[string]json.RawMessage
	if err := json.Unmarshal([]byte(frames[len(frames)-1].data), &raw); err != nil {
		t.Fatal(err)
	}
	if _, ok := raw["think_seqs"]; ok {
		t.Fatalf("default summary must not contain think_seqs")
	}
}

func TestStreamThinkInjectionInvalid(t *testing.T) {
	bad := map[string][]ThinkInjection{
		"at_seq 0":        {{AtSeq: 0, DwellUs: 10_000}}, // seq 0 前归 TTFT
		"at_seq >=tokens": {{AtSeq: 8, DwellUs: 10_000}}, // = tokens(8)
		"dwell 0":         {{AtSeq: 2, DwellUs: 0}},
		"dwell too big":   {{AtSeq: 2, DwellUs: 20_000_000}}, // > 10s
	}
	for name, injs := range bad {
		prof := &Profile{
			ProfileID: "tbad", Version: "t@1",
			Phases: []Phase{{Type: "token_stream", Tokens: 8, RateTps: 5000, Seed: 1, ThinkInjections: injs}},
		}
		a := &app{profiles: map[string]*Profile{"tbad": prof}, dataDir: t.TempDir()}
		srv := httptest.NewServer(a.routes())
		resp, err := http.Get(srv.URL + "/api/v1/stream?profile=tbad")
		if err != nil {
			srv.Close()
			t.Fatalf("%s: %v", name, err)
		}
		code := resp.StatusCode
		resp.Body.Close()
		srv.Close()
		if code != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400", name, code)
		}
	}
}
