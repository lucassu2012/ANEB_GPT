package main

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"runtime"
	"strings"
	"testing"
	"time"
)

// 无 ConnContext（httptest.NewRequest 的裸 context）→ 必须 n/a，绝不编造数值。
func TestConnTotalRetransWithoutConnContext(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/api/v1/stream", nil)
	if v, ok := connTotalRetrans(r); ok {
		t.Fatalf("no conn in context: got (%d, true), want ok=false", v)
	}
}

// startServerWithConnContext 用与 main() 相同的 ConnContext 配置起真实 TCP 服务
// （httptest.NewServer 不设 ConnContext，覆盖不到正向路径）。
func startServerWithConnContext(t *testing.T, a *app) (base string, shutdown func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: a.routes(), ConnContext: connContext}
	go srv.Serve(ln)
	return "http://" + ln.Addr().String(), func() {
		srv.Close()
		ln.Close()
	}
}

// summaryRetransProbe 只解 retrans_total 字段（指针区分"缺省"与"为 0"）。
type summaryRetransProbe struct {
	Tokens       int     `json:"tokens"`
	RetransTotal *uint64 `json:"retrans_total"`
}

// 平台语义合同：
//   - Linux：ConnContext 配好后 summary 必须携带 retrans_total（本机干净回环
//     环境应为 0；netem 丢包路径 >0 由 Docker 复验证据覆盖）；
//   - 非 Linux：字段必须整体缺省（n/a 分支），summary 其余合同不受影响。
func TestStreamSummaryRetransTotalPlatformContract(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	base, shutdown := startServerWithConnContext(t, a)
	defer shutdown()

	frames := fetchStream(t, base+"/api/v1/stream?tokens=5&rate_tps=2000&run=tcpinfo-test")
	if len(frames) != 5+2 {
		t.Fatalf("got %d frames, want 7", len(frames))
	}
	last := frames[len(frames)-1]
	if last.event != "summary" {
		t.Fatalf("last frame event = %q, want summary", last.event)
	}
	var sum summaryRetransProbe
	if err := json.Unmarshal([]byte(last.data), &sum); err != nil {
		t.Fatalf("summary JSON: %v (%q)", err, last.data)
	}
	if sum.Tokens != 5 {
		t.Fatalf("summary tokens = %d, want 5", sum.Tokens)
	}

	if runtime.GOOS == "linux" {
		if sum.RetransTotal == nil {
			t.Fatalf("linux + ConnContext: summary missing retrans_total (%q)", last.data)
		}
		// 本机回环干净路径正常为 0；只验证字段存在且非负（uint 天然非负）。
	} else {
		if sum.RetransTotal != nil {
			t.Fatalf("non-linux: summary must omit retrans_total, got %d", *sum.RetransTotal)
		}
		if strings.Contains(last.data, "retrans_total") {
			t.Fatalf("non-linux: summary text still contains retrans_total: %q", last.data)
		}
	}
}

// httptest.NewServer（未设 ConnContext）下 /stream 全平台必须走 n/a 分支——
// 既有测试环境的 summary 合同保持不变（零回归锚点）。
func TestStreamSummaryNoRetransWithoutConnContext(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	frames := fetchStream(t, srv.URL+"/api/v1/stream?tokens=3&rate_tps=2000")
	last := frames[len(frames)-1]
	if last.event != "summary" {
		t.Fatalf("last frame event = %q, want summary", last.event)
	}
	if strings.Contains(last.data, "retrans_total") {
		t.Fatalf("summary without ConnContext must omit retrans_total: %q", last.data)
	}
}

// 防御性：ConnContext 存包裹后连接类型（非 syscall.Conn）时不 panic、判 n/a。
type nonSyscallConn struct{ net.Conn }

func TestConnTotalRetransNonSyscallConn(t *testing.T) {
	c1, c2 := net.Pipe()
	defer c1.Close()
	defer c2.Close()
	r := httptest.NewRequest(http.MethodGet, "/api/v1/stream", nil)
	r = r.WithContext(connContext(r.Context(), nonSyscallConn{c1}))
	done := make(chan struct{})
	go func() { // net.Pipe 无 SyscallConn；确保调用即时返回不 hang
		defer close(done)
		if v, ok := connTotalRetrans(r); ok {
			t.Errorf("non-syscall conn: got (%d, true), want ok=false", v)
		}
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("connTotalRetrans hung on non-syscall conn")
	}
}
