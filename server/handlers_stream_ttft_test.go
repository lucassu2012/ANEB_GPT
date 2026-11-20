package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// preludeTtft 解析 prelude 注释帧的 ttft_inject_us / srv_ts_us。
type preludeTtft struct {
	SrvTsUs      int64 `json:"srv_ts_us"`
	TtftInjectUs int64 `json:"ttft_inject_us"`
}

func parsePreludeTtft(t *testing.T, frames []sseFrame) preludeTtft {
	t.Helper()
	var p preludeTtft
	if err := json.Unmarshal([]byte(strings.TrimPrefix(frames[0].comment, "prelude ")), &p); err != nil {
		t.Fatalf("prelude JSON: %v", err)
	}
	return p
}

// TTFT 注入透出（§3.4）：prelude 带 ttft_inject_us；首 token 计划相对流起点右移该值。
func TestStreamTtftInjectExposed(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	const inject = 20_000 // 20ms（保持测试快）
	frames := fetchStream(t, srv.URL+"/api/v1/stream?tokens=5&rate_tps=2000&ttft_inject_us=20000")
	pre := parsePreludeTtft(t, frames)
	if pre.TtftInjectUs != inject {
		t.Fatalf("prelude ttft_inject_us = %d, want %d", pre.TtftInjectUs, inject)
	}

	var first tokenData
	if err := json.Unmarshal([]byte(frames[1].data), &first); err != nil {
		t.Fatalf("first token JSON: %v", err)
	}
	var sum summaryData
	if err := json.Unmarshal([]byte(frames[len(frames)-1].data), &sum); err != nil {
		t.Fatalf("summary JSON: %v", err)
	}
	// sched_us[0] − stream_start_us == 注入 dwell（精确计算值，非测量）。
	if got := first.SchedUs - sum.StreamStartUs; got != inject {
		t.Fatalf("first token dwell = %d, want %d", got, inject)
	}
}

// 默认（不带参数）：ttft_inject_us=0，首 token 计划即流起点——行为不变（零回归）。
func TestStreamTtftInjectDefaultZero(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	frames := fetchStream(t, srv.URL+"/api/v1/stream?tokens=5&rate_tps=2000")
	pre := parsePreludeTtft(t, frames)
	if pre.TtftInjectUs != 0 {
		t.Fatalf("default ttft_inject_us = %d, want 0", pre.TtftInjectUs)
	}
	var first tokenData
	_ = json.Unmarshal([]byte(frames[1].data), &first)
	var sum summaryData
	_ = json.Unmarshal([]byte(frames[len(frames)-1].data), &sum)
	if got := first.SchedUs - sum.StreamStartUs; got != 0 {
		t.Fatalf("default first token dwell = %d, want 0", got)
	}
}

func TestStreamTtftInjectInvalid(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	for _, bad := range []string{"-1", "10000001", "abc"} {
		resp, err := http.Get(srv.URL + "/api/v1/stream?tokens=5&rate_tps=2000&ttft_inject_us=" + bad)
		if err != nil {
			t.Fatalf("get %q: %v", bad, err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("ttft_inject_us=%q status = %d, want 400", bad, resp.StatusCode)
		}
	}
}

// profile 声明 token_stream.ttft_inject_us：透出且首 token 右移。
func TestStreamTtftInjectFromProfile(t *testing.T) {
	prof := &Profile{
		ProfileID: "ttft_prof",
		Version:   "test@1",
		Phases: []Phase{
			{Type: "token_stream", Tokens: 5, RateTps: 2000, Seed: 1, TtftInjectUs: 15_000},
		},
	}
	a := &app{profiles: map[string]*Profile{"ttft_prof": prof}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	frames := fetchStream(t, srv.URL+"/api/v1/stream?profile=ttft_prof")
	pre := parsePreludeTtft(t, frames)
	if pre.TtftInjectUs != 15_000 {
		t.Fatalf("profile ttft_inject_us = %d, want 15000", pre.TtftInjectUs)
	}
}
