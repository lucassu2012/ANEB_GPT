package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

type echoResp struct {
	T1Us             int64  `json:"t1_us"`
	T2Us             int64  `json:"t2_us"`
	AnchorWallUnixNs int64  `json:"anchor_wall_unix_ns"`
	Observed         string `json:"observed"`
}

func postEcho(t *testing.T, url string, body string) echoResp {
	t.Helper()
	resp, err := http.Post(url+"/api/v1/echo", "application/octet-stream", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}
	var e echoResp
	if err := json.NewDecoder(resp.Body).Decode(&e); err != nil {
		t.Fatalf("decode: %v", err)
	}
	return e
}

// 时间戳单调且 t2 >= t1；连续请求间时间戳不回退（单调锚点，R-24）。
func TestEchoTimestampsMonotonic(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	var prevT2 int64 = -1
	for i := 0; i < 5; i++ {
		e := postEcho(t, srv.URL, "ping")
		if e.T1Us < 0 {
			t.Fatalf("t1_us negative: %d", e.T1Us)
		}
		if e.T2Us < e.T1Us {
			t.Fatalf("t2_us %d < t1_us %d", e.T2Us, e.T1Us)
		}
		if e.T1Us < prevT2 {
			t.Fatalf("t1_us %d went backwards past previous t2_us %d", e.T1Us, prevT2)
		}
		prevT2 = e.T2Us
		if e.AnchorWallUnixNs != anchorWallUnixNs {
			t.Fatalf("anchor mismatch: %d vs %d", e.AnchorWallUnixNs, anchorWallUnixNs)
		}
		if !strings.Contains(e.Observed, ":") {
			t.Fatalf("observed not ip:port: %q", e.Observed)
		}
	}
}

func TestEchoRejectsOversizedBody(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	resp, err := http.Post(srv.URL+"/api/v1/echo", "application/octet-stream",
		strings.NewReader(strings.Repeat("x", 512)))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("status %d, want 413", resp.StatusCode)
	}
}

func TestEchoMethodNotAllowed(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/v1/echo")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("status %d, want 405", resp.StatusCode)
	}
}
