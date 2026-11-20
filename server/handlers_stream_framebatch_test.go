package main

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
)

type summaryFrameData struct {
	Tokens         int     `json:"tokens"`
	TokensPerFrame int     `json:"tokens_per_frame"`
	FramesFlushed  int     `json:"frames_flushed"`
	FlushReturnUs  []int64 `json:"flush_return_us"`
	FlushBlockUs   []int64 `json:"flush_block_us"`
}

func fetchSummaryFrame(t *testing.T, frames []sseFrame) summaryFrameData {
	t.Helper()
	var s summaryFrameData
	if err := json.Unmarshal([]byte(frames[len(frames)-1].data), &s); err != nil {
		t.Fatalf("summary JSON: %v", err)
	}
	return s
}

func ceilDiv(a, b int) int { return (a + b - 1) / b }

// frames_flushed = ceil(n/K)，tokens_per_frame 回显（§3.2 合帧结构可核对）。
func TestStreamFrameBatchingCounts(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	cases := []struct{ n, k int }{
		{9, 3}, {10, 3}, {7, 3}, {5, 1}, {8, 4}, {6, 5},
	}
	for _, c := range cases {
		url := srv.URL + "/api/v1/stream?tokens=" + strconv.Itoa(c.n) + "&rate_tps=5000&tokens_per_frame=" + strconv.Itoa(c.k)
		frames := fetchStream(t, url)
		if len(frames) != c.n+2 {
			t.Fatalf("n=%d k=%d: %d frames, want %d", c.n, c.k, len(frames), c.n+2)
		}
		sum := fetchSummaryFrame(t, frames)
		if sum.TokensPerFrame != c.k {
			t.Fatalf("n=%d k=%d: tokens_per_frame = %d", c.n, c.k, sum.TokensPerFrame)
		}
		if want := ceilDiv(c.n, c.k); sum.FramesFlushed != want {
			t.Fatalf("n=%d k=%d: frames_flushed = %d, want %d", c.n, c.k, sum.FramesFlushed, want)
		}
	}
}

// 默认（无参数）：每 token 一帧，frames_flushed = n。
func TestStreamFrameBatchingDefaultOne(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	frames := fetchStream(t, srv.URL+"/api/v1/stream?tokens=6&rate_tps=5000")
	sum := fetchSummaryFrame(t, frames)
	if sum.TokensPerFrame != 1 {
		t.Fatalf("default tokens_per_frame = %d, want 1", sum.TokensPerFrame)
	}
	if sum.FramesFlushed != 6 {
		t.Fatalf("default frames_flushed = %d, want 6", sum.FramesFlushed)
	}
}

// 合帧不丢 token：K>1 时全部 N 个 token 仍在、seq 连续、payload 合法、summary 数组长度 = N。
func TestStreamFrameBatchingPreservesAllTokens(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	const n = 11
	frames := fetchStream(t, srv.URL+"/api/v1/stream?tokens=11&rate_tps=5000&tokens_per_frame=4")
	if len(frames) != n+2 {
		t.Fatalf("%d frames, want %d", len(frames), n+2)
	}
	for i := 0; i < n; i++ {
		var td tokenData
		if err := json.Unmarshal([]byte(frames[1+i].data), &td); err != nil {
			t.Fatalf("token %d: %v", i, err)
		}
		if td.Seq != i {
			t.Fatalf("seq %d at pos %d", td.Seq, i)
		}
		if _, err := base64.StdEncoding.DecodeString(td.Payload); err != nil {
			t.Fatalf("seq %d payload not base64", i)
		}
	}
	sum := fetchSummaryFrame(t, frames)
	if len(sum.FlushReturnUs) != n || len(sum.FlushBlockUs) != n {
		t.Fatalf("summary array len mismatch")
	}
	// flush_return 单调非递减（批内非边界 token 记写缓冲时刻，边界记真 flush 后时刻）。
	for i := 1; i < n; i++ {
		if sum.FlushReturnUs[i] < sum.FlushReturnUs[i-1] {
			t.Fatalf("flush_return not monotonic at %d", i)
		}
	}
}

func TestStreamFrameBatchingInvalid(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()
	for _, bad := range []string{"0", "65", "-1", "abc"} {
		resp, err := http.Get(srv.URL + "/api/v1/stream?tokens=5&rate_tps=5000&tokens_per_frame=" + bad)
		if err != nil {
			t.Fatalf("get %q: %v", bad, err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("tokens_per_frame=%q status = %d, want 400", bad, resp.StatusCode)
		}
	}
}

// profile 越界声明（tokens_per_frame=100）被拒。
func TestStreamFrameBatchingProfileOutOfRange(t *testing.T) {
	prof := &Profile{
		ProfileID: "fb_bad", Version: "t@1",
		Phases: []Phase{{Type: "token_stream", Tokens: 5, RateTps: 5000, Seed: 1, TokensPerFrame: 100}},
	}
	a := &app{profiles: map[string]*Profile{"fb_bad": prof}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/api/v1/stream?profile=fb_bad")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("profile tokens_per_frame=100 status = %d, want 400", resp.StatusCode)
	}
}

// profile 声明 tokens_per_frame：正常应用。
func TestStreamFrameBatchingFromProfile(t *testing.T) {
	prof := &Profile{
		ProfileID: "fb_prof", Version: "t@1",
		Phases: []Phase{{Type: "token_stream", Tokens: 8, RateTps: 5000, Seed: 1, TokensPerFrame: 4}},
	}
	a := &app{profiles: map[string]*Profile{"fb_prof": prof}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()
	frames := fetchStream(t, srv.URL+"/api/v1/stream?profile=fb_prof")
	sum := fetchSummaryFrame(t, frames)
	if sum.TokensPerFrame != 4 || sum.FramesFlushed != 2 {
		t.Fatalf("profile framing: tokens_per_frame=%d frames_flushed=%d, want 4/2", sum.TokensPerFrame, sum.FramesFlushed)
	}
}
