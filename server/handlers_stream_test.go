package main

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

type tokenData struct {
	Seq        int    `json:"seq"`
	SchedUs    int64  `json:"sched_us"`
	PreFlushUs int64  `json:"pre_flush_us"`
	Payload    string `json:"payload"`
}

type summaryData struct {
	Tokens        int     `json:"tokens"`
	StreamStartUs int64   `json:"stream_start_us"`
	FlushReturnUs []int64 `json:"flush_return_us"`
	TimerLateUs   []int64 `json:"timer_late_us"`
	FlushBlockUs  []int64 `json:"flush_block_us"`
	CarryoverUs   []int64 `json:"carryover_us"`
	InjectApplied string  `json:"inject_applied"` // 仅注入流携带
}

// sseFrame 是按 \n\n 切分出的一帧。
type sseFrame struct {
	comment string // ": ..." 注释帧内容（不含前缀）
	event   string // event: 值
	data    string // data: 值
}

func parseSSE(t *testing.T, body string) []sseFrame {
	t.Helper()
	var frames []sseFrame
	for _, raw := range strings.Split(body, "\n\n") {
		raw = strings.TrimRight(raw, "\n")
		if raw == "" {
			continue
		}
		var f sseFrame
		for _, line := range strings.Split(raw, "\n") {
			switch {
			case strings.HasPrefix(line, ": "):
				f.comment = strings.TrimPrefix(line, ": ")
			case strings.HasPrefix(line, "event: "):
				f.event = strings.TrimPrefix(line, "event: ")
			case strings.HasPrefix(line, "data: "):
				f.data = strings.TrimPrefix(line, "data: ")
			default:
				t.Fatalf("unexpected SSE line: %q", line)
			}
		}
		frames = append(frames, f)
	}
	return frames
}

func fetchStream(t *testing.T, url string) []sseFrame {
	t.Helper()
	resp, err := http.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); ct != "text/event-stream" {
		t.Fatalf("Content-Type = %q", ct)
	}
	if cc := resp.Header.Get("Cache-Control"); cc != "no-cache" {
		t.Fatalf("Cache-Control = %q", cc)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	return parseSSE(t, string(body))
}

// prelude 在首位、seq 连续 0..N-1、summary 在末位且数组长度 = N。
func TestStreamShape(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	const n = 20
	frames := fetchStream(t, srv.URL+"/api/v1/stream?tokens=20&rate_tps=2000&run=test-run")

	if len(frames) != n+2 {
		t.Fatalf("got %d frames, want %d (prelude + %d tokens + summary)", len(frames), n+2, n)
	}

	// R-20: prelude 必须是第一帧，且为注释帧，含 srv_ts_us 与 anchor_wall_unix_ns。
	pre := frames[0]
	if pre.event != "" || pre.comment == "" {
		t.Fatalf("first frame is not a comment prelude: %+v", pre)
	}
	if !strings.HasPrefix(pre.comment, "prelude ") {
		t.Fatalf("prelude comment malformed: %q", pre.comment)
	}
	var preObj struct {
		SrvTsUs          int64 `json:"srv_ts_us"`
		AnchorWallUnixNs int64 `json:"anchor_wall_unix_ns"`
	}
	if err := json.Unmarshal([]byte(strings.TrimPrefix(pre.comment, "prelude ")), &preObj); err != nil {
		t.Fatalf("prelude JSON: %v", err)
	}
	if preObj.SrvTsUs <= 0 || preObj.AnchorWallUnixNs != anchorWallUnixNs {
		t.Fatalf("prelude values wrong: %+v", preObj)
	}

	// token events：seq 连续 0..N-1，payload 是合法 base64，时间戳单调。
	var prevPreFlush int64 = -1
	for i := 0; i < n; i++ {
		f := frames[1+i]
		if f.event != "token" {
			t.Fatalf("frame %d event = %q, want token", 1+i, f.event)
		}
		var td tokenData
		if err := json.Unmarshal([]byte(f.data), &td); err != nil {
			t.Fatalf("token %d data JSON: %v (%q)", i, err, f.data)
		}
		if td.Seq != i {
			t.Fatalf("seq not contiguous: got %d at position %d", td.Seq, i)
		}
		raw, err := base64.StdEncoding.DecodeString(td.Payload)
		if err != nil {
			t.Fatalf("seq %d payload not base64: %v", i, err)
		}
		if len(raw) < tokenBytesMin || len(raw) > tokenBytesMax {
			t.Fatalf("seq %d payload size %d out of clamp", i, len(raw))
		}
		if td.PreFlushUs < prevPreFlush {
			t.Fatalf("pre_flush_us went backwards at seq %d: %d < %d", i, td.PreFlushUs, prevPreFlush)
		}
		if td.SchedUs < preObj.SrvTsUs {
			t.Fatalf("seq %d sched_us %d before prelude srv_ts_us %d", i, td.SchedUs, preObj.SrvTsUs)
		}
		prevPreFlush = td.PreFlushUs
	}

	// summary 在末位，四个数组长度 = N（timer 迟到、flush 阻塞、前序遗留
	// carryover 三种滞后分开记录，R-06 跨事件解耦）。
	last := frames[len(frames)-1]
	if last.event != "summary" {
		t.Fatalf("last frame event = %q, want summary", last.event)
	}
	var sum summaryData
	if err := json.Unmarshal([]byte(last.data), &sum); err != nil {
		t.Fatalf("summary JSON: %v", err)
	}
	if sum.Tokens != n {
		t.Fatalf("summary tokens = %d, want %d", sum.Tokens, n)
	}
	if len(sum.FlushReturnUs) != n || len(sum.TimerLateUs) != n ||
		len(sum.FlushBlockUs) != n || len(sum.CarryoverUs) != n {
		t.Fatalf("summary array lengths %d/%d/%d/%d, want %d",
			len(sum.FlushReturnUs), len(sum.TimerLateUs),
			len(sum.FlushBlockUs), len(sum.CarryoverUs), n)
	}
	for i := 1; i < n; i++ {
		if sum.FlushReturnUs[i] < sum.FlushReturnUs[i-1] {
			t.Fatalf("flush_return_us not monotonic at %d", i)
		}
	}
	for i := 0; i < n; i++ {
		if sum.FlushBlockUs[i] < 0 || sum.TimerLateUs[i] < 0 || sum.CarryoverUs[i] < 0 {
			t.Fatalf("negative summary value at %d: block=%d late=%d carryover=%d",
				i, sum.FlushBlockUs[i], sum.TimerLateUs[i], sum.CarryoverUs[i])
		}
	}
}

// 同 seed 两次请求 payload 序列一致（token 生成确定性穿透到线上格式）。
func TestStreamDeterministicPayloads(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	url := srv.URL + "/api/v1/stream?tokens=10&rate_tps=5000&seed=777"
	f1 := fetchStream(t, url)
	f2 := fetchStream(t, url)
	for i := 1; i <= 10; i++ {
		var a1, a2 tokenData
		if err := json.Unmarshal([]byte(f1[i].data), &a1); err != nil {
			t.Fatal(err)
		}
		if err := json.Unmarshal([]byte(f2[i].data), &a2); err != nil {
			t.Fatal(err)
		}
		if a1.Payload != a2.Payload {
			t.Fatalf("payload differs at seq %d for same seed", i-1)
		}
	}
}

// profile= 参数路径：token 数取自 profile 的 token_stream phase。
func TestStreamWithProfile(t *testing.T) {
	profiles := map[string]*Profile{
		"mini": {
			ProfileID: "mini", Version: "0.0.1", KpiSet: "test",
			Phases: []Phase{
				{Type: "clock_sync", Samples: 5},
				{Type: "token_stream", Tokens: 7, RateTps: 3000,
					TokenBytes: &TokenBytes{Dist: "lognormal", Median: 120, Sigma: 0.6},
					Seed:       99},
			},
		},
	}
	a := &app{profiles: profiles, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	frames := fetchStream(t, srv.URL+"/api/v1/stream?profile=mini&run=r1")
	if len(frames) != 7+2 {
		t.Fatalf("got %d frames, want 9", len(frames))
	}

	resp, err := http.Get(srv.URL + "/api/v1/stream?profile=nope")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("unknown profile: status %d, want 400", resp.StatusCode)
	}
}

// burst phase 显式给 rate_tps 必须 400（GenerateTokens 对 burst 忽略 RateTps，
// 静默接受会让调用方误以为已改变节奏）。
func TestStreamBurstRejectsRateTpsOverride(t *testing.T) {
	profiles := map[string]*Profile{
		"bursty": {
			ProfileID: "bursty", Version: "0.0.1", KpiSet: "test",
			Phases: []Phase{
				{Type: "token_stream", Tokens: 5, Seed: 7,
					TokenBytes: &TokenBytes{Dist: "lognormal", Median: 120, Sigma: 0.6},
					Burst:      &Burst{ClusterTps: 3000, PauseMs: []int{1, 2}, ClusterGeomP: 0.5}},
			},
		},
	}
	a := &app{profiles: profiles, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/v1/stream?profile=bursty&rate_tps=50")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("burst + rate_tps override: status %d, want 400", resp.StatusCode)
	}

	// 不带覆盖参数时同一 profile 正常工作。
	frames := fetchStream(t, srv.URL+"/api/v1/stream?profile=bursty")
	if len(frames) != 5+2 {
		t.Fatalf("got %d frames, want 7", len(frames))
	}
}

// 故障注入钩子（P0-C13 前置）：flag 关闭时带 inject 参数必须 403，
// 正常流（无 inject 参数）不受影响。
func TestStreamInjectRequiresFlag(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()} // allowInject 默认 false
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	for _, spec := range []string{"truncate:2", "malformed:2", "dupseq:2"} {
		resp, err := http.Get(srv.URL + "/api/v1/stream?tokens=5&rate_tps=2000&inject=" + spec)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusForbidden {
			t.Fatalf("inject=%s without -allow-inject: status %d, want 403", spec, resp.StatusCode)
		}
	}

	// 无 inject 参数的正常流不受 flag 缺席影响。
	frames := fetchStream(t, srv.URL+"/api/v1/stream?tokens=5&rate_tps=2000")
	if len(frames) != 5+2 {
		t.Fatalf("normal stream got %d frames, want 7", len(frames))
	}
	var sum summaryData
	if err := json.Unmarshal([]byte(frames[len(frames)-1].data), &sum); err != nil {
		t.Fatal(err)
	}
	if sum.InjectApplied != "" {
		t.Fatalf("normal stream summary carries inject_applied = %q", sum.InjectApplied)
	}
}

// truncate:N——发送 N 个 event 后直接关流：prelude + N token，无 summary。
func TestStreamInjectTruncate(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir(), allowInject: true}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	frames := fetchStream(t, srv.URL+"/api/v1/stream?tokens=8&rate_tps=2000&inject=truncate:3")
	if len(frames) != 1+3 {
		t.Fatalf("got %d frames, want 4 (prelude + 3 tokens, stream cut)", len(frames))
	}
	for i := 0; i < 3; i++ {
		f := frames[1+i]
		if f.event != "token" {
			t.Fatalf("frame %d event = %q, want token (and no summary)", 1+i, f.event)
		}
		var td tokenData
		if err := json.Unmarshal([]byte(f.data), &td); err != nil {
			t.Fatalf("token %d data JSON: %v", i, err)
		}
		if td.Seq != i {
			t.Fatalf("seq = %d at position %d", td.Seq, i)
		}
	}
}

// malformed:N——第 N 个 event 的 data JSON 被截断半个 payload，其余 event
// 与 summary 完好，summary 如实记录 inject_applied。
func TestStreamInjectMalformed(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir(), allowInject: true}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	const n = 5
	frames := fetchStream(t, srv.URL+"/api/v1/stream?tokens=5&rate_tps=2000&inject=malformed:3")
	if len(frames) != n+2 {
		t.Fatalf("got %d frames, want %d", len(frames), n+2)
	}
	for i := 0; i < n; i++ {
		f := frames[1+i]
		if f.event != "token" {
			t.Fatalf("frame %d event = %q, want token", 1+i, f.event)
		}
		var td tokenData
		err := json.Unmarshal([]byte(f.data), &td)
		if i == 2 { // 第 3 个 event（1-based N=3）
			if err == nil {
				t.Fatalf("event 3 data unexpectedly parses as JSON: %q", f.data)
			}
			continue
		}
		if err != nil {
			t.Fatalf("event %d (should be intact) data JSON: %v (%q)", i+1, err, f.data)
		}
		if td.Seq != i {
			t.Fatalf("seq = %d at position %d", td.Seq, i)
		}
	}
	var sum summaryData
	if err := json.Unmarshal([]byte(frames[n+1].data), &sum); err != nil {
		t.Fatalf("summary JSON: %v", err)
	}
	if sum.InjectApplied != "malformed:3" {
		t.Fatalf("inject_applied = %q, want malformed:3", sum.InjectApplied)
	}
}

// dupseq:N——第 N 个 event 的 seq 重复上一个，其余连续；summary 如实记录。
func TestStreamInjectDupseq(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir(), allowInject: true}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	const n = 5
	frames := fetchStream(t, srv.URL+"/api/v1/stream?tokens=5&rate_tps=2000&inject=dupseq:3")
	if len(frames) != n+2 {
		t.Fatalf("got %d frames, want %d", len(frames), n+2)
	}
	wantSeq := []int{0, 1, 1, 3, 4} // 第 3 个 event（0-based 2）重复 seq=1
	for i := 0; i < n; i++ {
		var td tokenData
		if err := json.Unmarshal([]byte(frames[1+i].data), &td); err != nil {
			t.Fatalf("token %d data JSON: %v", i, err)
		}
		if td.Seq != wantSeq[i] {
			t.Fatalf("position %d seq = %d, want %d", i, td.Seq, wantSeq[i])
		}
	}
	var sum summaryData
	if err := json.Unmarshal([]byte(frames[n+1].data), &sum); err != nil {
		t.Fatalf("summary JSON: %v", err)
	}
	if sum.InjectApplied != "dupseq:3" {
		t.Fatalf("inject_applied = %q, want dupseq:3", sum.InjectApplied)
	}
}

// 非法 inject 语法即使开了 flag 也是 400（dupseq:1 无前驱、未知类型、缺 N）。
func TestStreamInjectBadSyntax(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir(), allowInject: true}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	for _, spec := range []string{"dupseq:1", "explode:3", "truncate", "truncate:0", "truncate:x"} {
		resp, err := http.Get(srv.URL + "/api/v1/stream?tokens=5&rate_tps=2000&inject=" + spec)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("inject=%s: status %d, want 400", spec, resp.StatusCode)
		}
	}
}

// payloadSeq 提取 token 帧（frames[1..tokens]）的 payload 序列。
func payloadSeq(t *testing.T, frames []sseFrame, tokens int) []string {
	t.Helper()
	if len(frames) != tokens+2 {
		t.Fatalf("got %d frames, want %d (prelude + %d tokens + summary)", len(frames), tokens+2, tokens)
	}
	out := make([]string, tokens)
	for i := 0; i < tokens; i++ {
		var td tokenData
		if err := json.Unmarshal([]byte(frames[1+i].data), &td); err != nil {
			t.Fatalf("token %d data JSON: %v (%q)", i, err, frames[1+i].data)
		}
		if td.Seq != i {
			t.Fatalf("seq not contiguous: got %d at position %d", td.Seq, i)
		}
		out[i] = td.Payload
	}
	return out
}

// 并发流请求互不污染：3 goroutine 不同 seed 同时 GET /stream，各自的
// payload 序列必须与单独请求同 seed 时逐位一致（每连接状态隔离）。
func TestStreamConcurrent(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	seeds := []int{101, 202, 303}
	const tokens = 10
	urlFor := func(seed int) string {
		return srv.URL + "/api/v1/stream?tokens=10&rate_tps=2000&seed=" + strconv.Itoa(seed)
	}

	// 基线：单独（串行）请求各 seed 的 payload 序列。
	baseline := make(map[int][]string, len(seeds))
	for _, seed := range seeds {
		baseline[seed] = payloadSeq(t, fetchStream(t, urlFor(seed)), tokens)
	}

	// 并发请求：goroutine 内只做 IO 收集原始 body（t.Fatal 不能跨 goroutine），
	// 解析与断言回主 goroutine 做。
	bodies := make([]string, len(seeds))
	errs := make([]error, len(seeds))
	var wg sync.WaitGroup
	for idx, seed := range seeds {
		wg.Add(1)
		go func(idx, seed int) {
			defer wg.Done()
			resp, err := http.Get(urlFor(seed))
			if err != nil {
				errs[idx] = err
				return
			}
			defer resp.Body.Close()
			if resp.StatusCode != http.StatusOK {
				errs[idx] = fmt.Errorf("status %d", resp.StatusCode)
				return
			}
			b, err := io.ReadAll(resp.Body)
			if err != nil {
				errs[idx] = err
				return
			}
			bodies[idx] = string(b)
		}(idx, seed)
	}
	wg.Wait()
	for idx, err := range errs {
		if err != nil {
			t.Fatalf("concurrent stream seed %d: %v", seeds[idx], err)
		}
	}

	for idx, seed := range seeds {
		got := payloadSeq(t, parseSSE(t, bodies[idx]), tokens)
		want := baseline[seed]
		for i := range want {
			if got[i] != want[i] {
				t.Fatalf("seed %d payload differs at seq %d under concurrency (cross-request state pollution?)", seed, i)
			}
		}
	}
}

// 逐 event Flush 的实时性验证（R-04/R-17，审核要点⑦）：不信任服务端自报
// 时间戳，用低速率流逐帧读取并记录每帧到达的真实墙钟时刻。若实现把全部
// 内容攒在缓冲、最后统一 flush 一次，最终字节流与逐帧 Flush 完全相同，
// io.ReadAll 式测试无法区分——但所有帧会同时到达（帧间间隔≈0、总跨度≈0），
// 本测试必失败。误删循环内 flusher.Flush() 时本测试是唯一防线。
func TestStreamPerEventFlushPacing(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	const (
		tokens   = 6
		interval = 50 * time.Millisecond // rate_tps=20
	)
	resp, err := http.Get(srv.URL + "/api/v1/stream?tokens=6&rate_tps=20&seed=42")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}

	// 逐帧读取：以空行（\n）为帧终止符，帧完成即打真实到达时刻。
	type timedFrame struct {
		frame sseFrame
		at    time.Time
	}
	reader := bufio.NewReader(resp.Body)
	var got []timedFrame
	for {
		var sb strings.Builder
		var readErr error
		for {
			line, err := reader.ReadString('\n')
			sb.WriteString(line)
			if err != nil {
				readErr = err
				break
			}
			if line == "\n" {
				break // 空行 = 帧结束
			}
		}
		at := time.Now() // 帧完整到达时刻（解析之前取值）
		if raw := sb.String(); strings.TrimSpace(raw) != "" {
			frames := parseSSE(t, raw)
			if len(frames) != 1 {
				t.Fatalf("expected exactly 1 frame per read, got %d (%q)", len(frames), raw)
			}
			got = append(got, timedFrame{frame: frames[0], at: at})
		}
		if readErr != nil {
			if readErr != io.EOF {
				t.Fatalf("read stream: %v", readErr)
			}
			break
		}
	}

	if len(got) != tokens+2 {
		t.Fatalf("got %d frames, want %d (prelude + %d tokens + summary)", len(got), tokens+2, tokens)
	}
	arrival := make([]time.Time, tokens)
	for i := 0; i < tokens; i++ {
		f := got[1+i]
		if f.frame.event != "token" {
			t.Fatalf("frame %d event = %q, want token", 1+i, f.frame.event)
		}
		arrival[i] = f.at
	}

	// 计划跨度 = (N-1)*interval。sleep 只会晚不会早，故实际跨度不应显著
	// 小于计划值；攒缓冲实现的跨度≈0。阈值取计划值一半，容忍调度抖动。
	span := arrival[tokens-1].Sub(arrival[0])
	planned := time.Duration(tokens-1) * interval
	if span < planned/2 {
		t.Fatalf("token arrival span %v < planned %v / 2 — events not flushed per-event (buffered until end?)", span, planned)
	}
	// 相邻到达间隔应接近计划间隔：允许至多 2 个间隔因读端调度毛刺被合并
	// （<interval/5），全部间隔≈0 即为攒缓冲实现。
	small := 0
	for i := 1; i < tokens; i++ {
		if arrival[i].Sub(arrival[i-1]) < interval/5 {
			small++
		}
	}
	if small > 2 {
		t.Fatalf("%d of %d adjacent arrival gaps < %v — stream not paced per event", small, tokens-1, interval/5)
	}
}
