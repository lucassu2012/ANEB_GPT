package main

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
)

type artifactChunk struct {
	Seq        int    `json:"seq"`
	SchedUs    int64  `json:"sched_us"`
	PreFlushUs int64  `json:"pre_flush_us"`
	Bytes      int    `json:"bytes"`
	Payload    string `json:"payload"`
}

type artifactPrelude struct {
	SrvTsUs       int64  `json:"srv_ts_us"`
	ArtifactClass string `json:"artifact_class"`
	TotalBytes    int64  `json:"total_bytes"`
	CadenceBps    int64  `json:"cadence_bps"`
}

type artifactSummary struct {
	TotalBytes    int64   `json:"total_bytes"`
	Chunks        int     `json:"chunks"`
	CadenceBps    int64   `json:"cadence_bps"`
	StreamStartUs int64   `json:"stream_start_us"`
	FlushReturnUs []int64 `json:"flush_return_us"`
	TimerLateUs   []int64 `json:"timer_late_us"`
}

// prelude 首位 + N 个 chunk（seq 连续、bytes 合计=total）+ summary 末位；节奏时戳单调。
func TestArtifactStreamShape(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	// 1MiB @ 64KiB chunk = 16 chunk；高 cadence 保持测试快。
	const total = 1 << 20
	frames := fetchStream(t, srv.URL+"/api/v1/artifact_stream?bytes=1048576&chunk_kb=64&cadence_bps=1000000000&class=image")
	if len(frames) != 16+2 {
		t.Fatalf("got %d frames, want 18 (prelude + 16 chunk + summary)", len(frames))
	}
	var pre artifactPrelude
	if err := json.Unmarshal([]byte(strings.TrimPrefix(frames[0].comment, "prelude ")), &pre); err != nil {
		t.Fatalf("prelude JSON: %v", err)
	}
	if pre.TotalBytes != total || pre.CadenceBps != 1_000_000_000 || pre.ArtifactClass != "image" {
		t.Fatalf("prelude wrong: %+v", pre)
	}

	var sumBytes int64
	var prevSched int64 = -1
	for i := 0; i < 16; i++ {
		f := frames[1+i]
		if f.event != "chunk" {
			t.Fatalf("frame %d event = %q, want chunk", 1+i, f.event)
		}
		var c artifactChunk
		if err := json.Unmarshal([]byte(f.data), &c); err != nil {
			t.Fatalf("chunk %d JSON: %v", i, err)
		}
		if c.Seq != i {
			t.Fatalf("seq %d at pos %d", c.Seq, i)
		}
		raw, err := base64.StdEncoding.DecodeString(c.Payload)
		if err != nil || len(raw) != c.Bytes {
			t.Fatalf("chunk %d payload/bytes mismatch: len=%d bytes=%d err=%v", i, len(raw), c.Bytes, err)
		}
		if c.SchedUs < prevSched {
			t.Fatalf("sched_us not monotonic at %d", i)
		}
		prevSched = c.SchedUs
		sumBytes += int64(c.Bytes)
	}
	if sumBytes != total {
		t.Fatalf("chunk bytes sum = %d, want %d", sumBytes, total)
	}

	var sum artifactSummary
	if err := json.Unmarshal([]byte(frames[len(frames)-1].data), &sum); err != nil {
		t.Fatalf("summary JSON: %v", err)
	}
	if sum.TotalBytes != total || sum.Chunks != 16 {
		t.Fatalf("summary wrong: %+v", sum)
	}
	if len(sum.FlushReturnUs) != 16 || len(sum.TimerLateUs) != 16 {
		t.Fatalf("summary array len mismatch")
	}
}

// 末块不足整块：3000 字节 @ 1KiB... 用 16KiB chunk 与非整除总量验证末块尺寸。
func TestArtifactStreamPartialLastChunk(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	const total = 16*1024 + 500 // 1 整块 + 500B
	frames := fetchStream(t, srv.URL+"/api/v1/artifact_stream?bytes="+strconv.Itoa(total)+"&chunk_kb=16&cadence_bps=1000000000")
	// 2 chunk
	if len(frames) != 2+2 {
		t.Fatalf("got %d frames, want 4", len(frames))
	}
	var last artifactChunk
	if err := json.Unmarshal([]byte(frames[2].data), &last); err != nil {
		t.Fatal(err)
	}
	if last.Bytes != 500 {
		t.Fatalf("last chunk bytes = %d, want 500", last.Bytes)
	}
}

// 生成节奏限速：低 cadence 下 sched 跨度 ≈ bytes/cadence（不含末块）。
func TestArtifactStreamCadencePacing(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	// 256KiB @ 64KiB chunk = 4 chunk；cadence 4MB/s → 每块间隔 64KiB/4MBps=16384us。
	frames := fetchStream(t, srv.URL+"/api/v1/artifact_stream?bytes=262144&chunk_kb=64&cadence_bps=4000000")
	var c0, c1 artifactChunk
	_ = json.Unmarshal([]byte(frames[1].data), &c0)
	_ = json.Unmarshal([]byte(frames[2].data), &c1)
	gap := c1.SchedUs - c0.SchedUs
	// 65536 字节 / 4e6 Bps = 16384 us（sched 为精确计算值）。
	if gap != 65536*1_000_000/4_000_000 {
		t.Fatalf("chunk sched gap = %d, want %d", gap, 65536*1_000_000/4_000_000)
	}
}

func TestArtifactStreamInvalid(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()
	bad := []string{
		"bytes=0",
		"bytes=99999999999",                 // > artifactMaxBytes
		"chunk_kb=1",                        // < min
		"chunk_kb=9999",                     // > max
		"cadence_bps=1",                     // < min
		"bytes=33554432&cadence_bps=100000", // 32MiB / 0.1MBps = 335s > 60s
		"class=" + strings.Repeat("x", 40),  // class too long
	}
	for _, q := range bad {
		resp, err := http.Get(srv.URL + "/api/v1/artifact_stream?" + q)
		if err != nil {
			t.Fatalf("%s: %v", q, err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400", q, resp.StatusCode)
		}
	}
}

func TestArtifactStreamFromProfile(t *testing.T) {
	prof := &Profile{
		ProfileID: "art_prof", Version: "t@1",
		Phases: []Phase{{
			Type: "artifact_stream", Bytes: 128 * 1024, ChunkKB: 32,
			CadenceBps: 1_000_000_000, ArtifactClass: "video", Seed: 3,
		}},
	}
	a := &app{profiles: map[string]*Profile{"art_prof": prof}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()
	frames := fetchStream(t, srv.URL+"/api/v1/artifact_stream?profile=art_prof")
	var pre artifactPrelude
	if err := json.Unmarshal([]byte(strings.TrimPrefix(frames[0].comment, "prelude ")), &pre); err != nil {
		t.Fatal(err)
	}
	if pre.TotalBytes != 128*1024 || pre.ArtifactClass != "video" {
		t.Fatalf("profile prelude wrong: %+v", pre)
	}
	// 128KiB / 32KiB = 4 chunk
	if len(frames) != 4+2 {
		t.Fatalf("got %d frames, want 6", len(frames))
	}
}

func TestArtifactStreamDeterministicPayload(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()
	url := srv.URL + "/api/v1/artifact_stream?bytes=131072&chunk_kb=32&cadence_bps=1000000000&seed=99"
	f1 := fetchStream(t, url)
	f2 := fetchStream(t, url)
	for i := 1; i <= 4; i++ {
		var a1, a2 artifactChunk
		_ = json.Unmarshal([]byte(f1[i].data), &a1)
		_ = json.Unmarshal([]byte(f2[i].data), &a2)
		if a1.Payload != a2.Payload {
			t.Fatalf("payload differs at chunk %d for same seed", i-1)
		}
	}
}
