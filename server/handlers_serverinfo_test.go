package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"runtime"
	"strings"
	"testing"
)

func TestServerInfo(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/v1/serverinfo")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d, want 200", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); ct != "application/json" {
		t.Fatalf("Content-Type = %q", ct)
	}

	var info serverInfo
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if info.Version != serverVersion {
		t.Fatalf("version = %q, want %q", info.Version, serverVersion)
	}
	// 单调锚点映射：anchor 必须与进程全局一致，srv_ts_us 为正且随请求前进。
	if info.AnchorWallUnixNs != anchorWallUnixNs {
		t.Fatalf("anchor_wall_unix_ns = %d, want %d", info.AnchorWallUnixNs, anchorWallUnixNs)
	}
	if info.SrvTsUs <= 0 {
		t.Fatalf("srv_ts_us = %d, want > 0", info.SrvTsUs)
	}
	if info.UptimeS < 0 || info.UptimeS > info.SrvTsUs/1_000_000 {
		t.Fatalf("uptime_s = %d inconsistent with srv_ts_us = %d", info.UptimeS, info.SrvTsUs)
	}
	if info.Goos != runtime.GOOS || info.Goarch != runtime.GOARCH {
		t.Fatalf("goos/goarch = %s/%s, want %s/%s", info.Goos, info.Goarch, runtime.GOOS, runtime.GOARCH)
	}

	// /proc 读数：非 Linux 必须是 "n/a"；Linux 上要么 "n/a"（容器等读不到）
	// 要么非空真实读数——绝不允许空串或猜测值。
	for name, v := range map[string]string{
		"tcp_slow_start_after_idle": info.TCPSlowStartAfterIdle,
		"congestion_control":        info.CongestionControl,
	} {
		if runtime.GOOS != "linux" {
			if v != "n/a" {
				t.Fatalf("%s = %q on %s, want n/a", name, v, runtime.GOOS)
			}
		} else if strings.TrimSpace(v) == "" {
			t.Fatalf("%s is empty, want reading or n/a", name)
		}
	}

	// 第二次请求 srv_ts_us 不回退（单调）。
	resp2, err := http.Get(srv.URL + "/api/v1/serverinfo")
	if err != nil {
		t.Fatal(err)
	}
	defer resp2.Body.Close()
	var info2 serverInfo
	if err := json.NewDecoder(resp2.Body).Decode(&info2); err != nil {
		t.Fatal(err)
	}
	if info2.SrvTsUs < info.SrvTsUs {
		t.Fatalf("srv_ts_us went backwards: %d -> %d", info.SrvTsUs, info2.SrvTsUs)
	}
}

func TestServerInfoMethodNotAllowed(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	resp, err := http.Post(srv.URL+"/api/v1/serverinfo", "application/json", strings.NewReader("{}"))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("POST status %d, want 405", resp.StatusCode)
	}
}
