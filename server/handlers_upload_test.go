package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

// 分块到达时刻单调、字节数正确、区间边界一致（R-07）。
func TestUploadChunkTimesMonotonic(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	const size = 300 * 1024
	body := bytes.Repeat([]byte{0xAB}, size)
	resp, err := http.Post(srv.URL+"/api/v1/upload?run=r1", "application/octet-stream", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}
	var ur uploadResponse
	if err := json.NewDecoder(resp.Body).Decode(&ur); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if ur.Bytes != size {
		t.Fatalf("bytes = %d, want %d", ur.Bytes, size)
	}
	if len(ur.ChunkUs) == 0 {
		t.Fatal("no chunk timestamps recorded")
	}
	if ur.RecvStartUs > ur.ChunkUs[0] {
		t.Fatalf("recv_start_us %d after first chunk %d", ur.RecvStartUs, ur.ChunkUs[0])
	}
	prev := ur.ChunkUs[0]
	for i, ts := range ur.ChunkUs {
		if ts < prev {
			t.Fatalf("chunk_us not monotonic at %d: %d < %d", i, ts, prev)
		}
		prev = ts
	}
	if ur.RecvEndUs < prev {
		t.Fatalf("recv_end_us %d before last chunk %d", ur.RecvEndUs, prev)
	}
	if ur.Observed == "" {
		t.Fatal("observed empty")
	}
}

func TestToolLoop(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	up := bytes.Repeat([]byte{0x01}, 8192)
	resp, err := http.Post(srv.URL+"/api/v1/toolloop?proc_ms=50&down_bytes=2048",
		"application/octet-stream", bytes.NewReader(up))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if len(body) != 2048 {
		t.Fatalf("body len = %d, want 2048", len(body))
	}
	var trecv, tsend int64
	if _, err := parseInt64Header(resp, "X-Aneb-Trecv-Us", &trecv); err != nil {
		t.Fatal(err)
	}
	if _, err := parseInt64Header(resp, "X-Aneb-Tsend-Us", &tsend); err != nil {
		t.Fatal(err)
	}
	if tsend < trecv {
		t.Fatalf("tsend %d < trecv %d", tsend, trecv)
	}
	// 绝对 deadline 等待 proc_ms=50：处理时长必须 >= 50ms。
	if dur := tsend - trecv; dur < 50_000 {
		t.Fatalf("proc duration %dus < 50ms", dur)
	}
}

func parseInt64Header(resp *http.Response, name string, out *int64) (string, error) {
	s := resp.Header.Get(name)
	if s == "" {
		return s, &headerErr{name}
	}
	var v int64
	for _, c := range []byte(s) {
		if c < '0' || c > '9' {
			return s, &headerErr{name}
		}
		v = v*10 + int64(c-'0')
	}
	*out = v
	return s, nil
}

type headerErr struct{ name string }

func (e *headerErr) Error() string { return "missing or malformed header " + e.name }
