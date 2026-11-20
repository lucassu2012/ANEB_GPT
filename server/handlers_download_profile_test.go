package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
)

// /download?profile=&phase= 从 download_burst 相位取 bytes（单一事实源，§2.4/§3.1）。
func TestDownloadFromProfileBurstPhase(t *testing.T) {
	const declared = 2 << 20 // 2MiB
	prof := &Profile{
		ProfileID: "dl_prof", Version: "t@1",
		Phases: []Phase{
			{Type: "clock_sync", Samples: 5},
			{Type: "download_burst", Bytes: declared, ChunkKB: 128},
		},
	}
	a := &app{profiles: map[string]*Profile{"dl_prof": prof}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/v1/download?profile=dl_prof")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}
	if got := resp.Header.Get("Content-Length"); got != strconv.Itoa(declared) {
		t.Fatalf("Content-Length = %q, want %d (from profile)", got, declared)
	}
	read, _ := io.Copy(io.Discard, resp.Body)
	if read != declared {
		t.Fatalf("read %d, want %d", read, declared)
	}
}

// query bytes 覆盖 profile 声明。
func TestDownloadQueryOverridesProfile(t *testing.T) {
	prof := &Profile{
		ProfileID: "dl_ov", Version: "t@1",
		Phases: []Phase{{Type: "download_burst", Bytes: 2 << 20}},
	}
	a := &app{profiles: map[string]*Profile{"dl_ov": prof}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	const override = 1 << 20
	resp, err := http.Get(srv.URL + "/api/v1/download?profile=dl_ov&bytes=" + strconv.Itoa(override))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if got := resp.Header.Get("Content-Length"); got != strconv.Itoa(override) {
		t.Fatalf("Content-Length = %q, want %d (query override)", got, override)
	}
}

// 第 phase 个 download_burst：多相位按序号选取。
func TestDownloadProfilePhaseIndex(t *testing.T) {
	prof := &Profile{
		ProfileID: "dl_multi", Version: "t@1",
		Phases: []Phase{
			{Type: "download_burst", Bytes: 1 << 20},
			{Type: "token_stream", Tokens: 5, RateTps: 40, Seed: 1},
			{Type: "download_burst", Bytes: 4 << 20},
		},
	}
	a := &app{profiles: map[string]*Profile{"dl_multi": prof}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/v1/download?profile=dl_multi&phase=1")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if got := resp.Header.Get("Content-Length"); got != strconv.Itoa(4<<20) {
		t.Fatalf("Content-Length = %q, want %d (2nd download_burst)", got, 4<<20)
	}
}

func TestDownloadProfileErrors(t *testing.T) {
	prof := &Profile{
		ProfileID: "dl_err", Version: "t@1",
		Phases: []Phase{{Type: "token_stream", Tokens: 5, RateTps: 40, Seed: 1}}, // 无 download_burst
	}
	oversize := &Profile{
		ProfileID: "dl_big", Version: "t@1",
		Phases: []Phase{{Type: "download_burst", Bytes: downloadMaxBytes + 1}},
	}
	oversizeChunk := &Profile{ // Bytes 合法以隔离到 chunk_kb 越界分支
		ProfileID: "dl_chunk", Version: "t@1",
		Phases: []Phase{{Type: "download_burst", Bytes: 1 << 20, ChunkKB: downloadMaxChunkKB + 1}},
	}
	a := &app{profiles: map[string]*Profile{"dl_err": prof, "dl_big": oversize, "dl_chunk": oversizeChunk}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	cases := []string{
		"profile=nope",           // 未知 profile
		"profile=dl_err",         // 无 download_burst 相位
		"profile=dl_err&phase=x", // 非法 phase
		"profile=dl_big",         // 声明 bytes 越界
		"profile=dl_chunk",       // 声明 chunk_kb 越界
	}
	for _, q := range cases {
		resp, err := http.Get(srv.URL + "/api/v1/download?" + q)
		if err != nil {
			t.Fatalf("%s: %v", q, err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("%s: status = %d, want 400", q, resp.StatusCode)
		}
	}
}

// 无 profile：仍走全局缺省（零回归）。
func TestDownloadNoProfileUsesDefault(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/v1/download?bytes=1048576")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}
	if got := resp.Header.Get("Content-Length"); got != "1048576" {
		t.Fatalf("Content-Length = %q, want 1048576", got)
	}
}
