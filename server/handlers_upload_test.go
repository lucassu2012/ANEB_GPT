package main

import (
	"bytes"
	"encoding/json"
	"errors"
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

var errInjectedBodyRead = errors.New("injected body read failure")

// terminalReader 模拟底层 Reader 在最后一批字节上同时返回终止状态。
// n>0 与 error 同时返回是 io.Reader 的合法行为。
type terminalReader struct {
	data        []byte
	terminalErr error
	done        bool
}

func (r *terminalReader) Read(p []byte) (int, error) {
	if r.done {
		return 0, io.EOF
	}
	r.done = true
	return copy(p, r.data), r.terminalErr
}

func TestUploadRejectsPartialBodyReadFailure(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/upload", &terminalReader{
		data:        []byte("partial"),
		terminalErr: errInjectedBodyRead,
	})
	rec := httptest.NewRecorder()

	a.handleUpload(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status %d, want %d (body %q)", rec.Code, http.StatusBadRequest, rec.Body.String())
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte("body unreadable")) {
		t.Fatalf("body %q does not describe unreadable request", rec.Body.String())
	}
}

func TestToolLoopRejectsPartialBodyReadFailure(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/toolloop?proc_ms=0", &terminalReader{
		data:        []byte("partial"),
		terminalErr: errInjectedBodyRead,
	})
	rec := httptest.NewRecorder()

	a.handleToolLoop(rec, req)

	if rec.Code != http.StatusBadRequest {
		t.Fatalf("status %d, want %d (body %q)", rec.Code, http.StatusBadRequest, rec.Body.String())
	}
	if !bytes.Contains(rec.Body.Bytes(), []byte("body unreadable")) {
		t.Fatalf("body %q does not describe unreadable request", rec.Body.String())
	}
	if got := rec.Header().Get("X-Aneb-Trecv-Us"); got != "" {
		t.Fatalf("failed request exposed success timing header %q", got)
	}
	if got := rec.Header().Get("X-Aneb-Tsend-Us"); got != "" {
		t.Fatalf("failed request exposed success timing header %q", got)
	}
}

func TestHandlersAcceptExactEOFWithFinalBytes(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}

	t.Run("upload", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/upload", &terminalReader{
			data:        []byte("complete"),
			terminalErr: io.EOF,
		})
		rec := httptest.NewRecorder()

		a.handleUpload(rec, req)

		if rec.Code != http.StatusOK {
			t.Fatalf("status %d, want %d (body %q)", rec.Code, http.StatusOK, rec.Body.String())
		}
		var response uploadResponse
		if err := json.NewDecoder(rec.Body).Decode(&response); err != nil {
			t.Fatalf("decode response: %v", err)
		}
		if response.Bytes != int64(len("complete")) {
			t.Fatalf("bytes %d, want %d", response.Bytes, len("complete"))
		}
	})

	t.Run("toolloop", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/toolloop?proc_ms=0&down_bytes=0", &terminalReader{
			data:        []byte("complete"),
			terminalErr: io.EOF,
		})
		rec := httptest.NewRecorder()

		a.handleToolLoop(rec, req)

		if rec.Code != http.StatusOK {
			t.Fatalf("status %d, want %d (body %q)", rec.Code, http.StatusOK, rec.Body.String())
		}
		if rec.Header().Get("X-Aneb-Trecv-Us") == "" || rec.Header().Get("X-Aneb-Tsend-Us") == "" {
			t.Fatal("successful request is missing timing headers")
		}
	})
}

func TestHandlersRejectCompositeEOFReadFailure(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	terminalErr := errors.Join(io.EOF, errInjectedBodyRead)
	tests := []struct {
		name string
		path string
		run  func(http.ResponseWriter, *http.Request)
	}{
		{name: "upload", path: "/api/v1/upload", run: a.handleUpload},
		{name: "toolloop", path: "/api/v1/toolloop?proc_ms=0", run: a.handleToolLoop},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodPost, tt.path, &terminalReader{
				data:        []byte("partial"),
				terminalErr: terminalErr,
			})
			rec := httptest.NewRecorder()

			tt.run(rec, req)

			if rec.Code != http.StatusBadRequest {
				t.Fatalf("status %d, want %d (body %q)", rec.Code, http.StatusBadRequest, rec.Body.String())
			}
			if !bytes.Contains(rec.Body.Bytes(), []byte("body unreadable")) {
				t.Fatalf("body %q does not describe unreadable request", rec.Body.String())
			}
			if got := rec.Header().Get("X-Aneb-Trecv-Us"); got != "" {
				t.Fatalf("failed request exposed success timing header %q", got)
			}
			if got := rec.Header().Get("X-Aneb-Tsend-Us"); got != "" {
				t.Fatalf("failed request exposed success timing header %q", got)
			}
		})
	}
}

// repeatingReader 生成任意长度的请求体而不在测试中分配 64MB 切片。
type repeatingReader struct{}

func (repeatingReader) Read(p []byte) (int, error) {
	return len(p), nil
}

func TestUploadBodyLimit(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	tests := []struct {
		name       string
		size       int64
		wantStatus int
	}{
		{name: "exact limit", size: uploadMaxBytes, wantStatus: http.StatusOK},
		{name: "over limit", size: uploadMaxBytes + 1, wantStatus: http.StatusRequestEntityTooLarge},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodPost, "/api/v1/upload", io.LimitReader(repeatingReader{}, tt.size))
			rec := httptest.NewRecorder()

			a.handleUpload(rec, req)

			if rec.Code != tt.wantStatus {
				t.Fatalf("status %d, want %d (body %q)", rec.Code, tt.wantStatus, rec.Body.String())
			}
		})
	}
}

func TestToolLoopRejectsBodyOverLimit(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	req := httptest.NewRequest(
		http.MethodPost,
		"/api/v1/toolloop?proc_ms=0&down_bytes=0",
		io.LimitReader(repeatingReader{}, uploadMaxBytes+1),
	)
	rec := httptest.NewRecorder()

	a.handleToolLoop(rec, req)

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("status %d, want %d (body %q)", rec.Code, http.StatusRequestEntityTooLarge, rec.Body.String())
	}
	if got := rec.Header().Get("X-Aneb-Trecv-Us"); got != "" {
		t.Fatalf("failed request exposed success timing header %q", got)
	}
	if got := rec.Header().Get("X-Aneb-Tsend-Us"); got != "" {
		t.Fatalf("failed request exposed success timing header %q", got)
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
