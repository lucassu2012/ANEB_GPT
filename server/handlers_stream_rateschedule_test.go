package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// 非平稳解码曲线经 profile 声明：/stream 正常产出、间隔随进度增长。
func TestStreamRateScheduleFromProfile(t *testing.T) {
	prof := &Profile{
		ProfileID: "rs_prof",
		Version:   "test@1",
		Phases: []Phase{
			// 高 TPS 保持测试快；5000→2000 tps → 间隔 200us→500us。
			{Type: "token_stream", Tokens: 6, RateTps: 4000, Seed: 1,
				RateSchedule: []RatePoint{{AtFrac: 0, Tps: 5000}, {AtFrac: 1, Tps: 2000}}},
		},
	}
	a := &app{profiles: map[string]*Profile{"rs_prof": prof}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	frames := fetchStream(t, srv.URL+"/api/v1/stream?profile=rs_prof")
	// prelude + 6 token + summary
	if len(frames) != 8 {
		t.Fatalf("got %d frames, want 8", len(frames))
	}
	var scheds []int64
	for i := 1; i <= 6; i++ {
		var td tokenData
		if err := json.Unmarshal([]byte(frames[i].data), &td); err != nil {
			t.Fatalf("token %d: %v", i, err)
		}
		scheds = append(scheds, td.SchedUs)
	}
	// 间隔非递减且末段大于首段（非平稳）。
	firstGap := scheds[1] - scheds[0]
	lastGap := scheds[5] - scheds[4]
	if lastGap <= firstGap {
		t.Fatalf("expected growing gaps: firstGap=%d lastGap=%d", firstGap, lastGap)
	}
}

func TestStreamRateScheduleInvalid(t *testing.T) {
	badSchedules := map[string][]RatePoint{
		"at_frac>1":    {{AtFrac: 1.5, Tps: 100}},
		"at_frac<0":    {{AtFrac: -0.1, Tps: 100}},
		"tps too low":  {{AtFrac: 0, Tps: 0.01}},
		"non-monotone": {{AtFrac: 0.8, Tps: 100}, {AtFrac: 0.2, Tps: 50}},
	}
	for name, sched := range badSchedules {
		prof := &Profile{
			ProfileID: "bad", Version: "t@1",
			Phases: []Phase{{Type: "token_stream", Tokens: 5, RateTps: 40, Seed: 1, RateSchedule: sched}},
		}
		a := &app{profiles: map[string]*Profile{"bad": prof}, dataDir: t.TempDir()}
		srv := httptest.NewServer(a.routes())
		resp, err := http.Get(srv.URL + "/api/v1/stream?profile=bad")
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

func TestStreamRateScheduleRejectedWithBurst(t *testing.T) {
	prof := &Profile{
		ProfileID: "burst_rs", Version: "t@1",
		Phases: []Phase{{
			Type: "token_stream", Tokens: 10, Seed: 1,
			Burst:        &Burst{ClusterTps: 100, PauseMs: []int{300, 800}, ClusterGeomP: 0.2},
			RateSchedule: []RatePoint{{AtFrac: 0, Tps: 100}, {AtFrac: 1, Tps: 50}},
		}},
	}
	a := &app{profiles: map[string]*Profile{"burst_rs": prof}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/api/v1/stream?profile=burst_rs")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("rate_schedule+burst status = %d, want 400", resp.StatusCode)
	}
}
