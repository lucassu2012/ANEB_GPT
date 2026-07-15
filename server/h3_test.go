package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/quic-go/quic-go/http3"
)

// ---------- 测试基建：内存自签证书 + 本地端口 h3 服务器 ----------

// testCertDER 生成一张仅测试用的自签证书（P-256，SAN=127.0.0.1）。
func testCertDER(t *testing.T) (derBytes []byte, key *ecdsa.PrivateKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "aneb-test"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IPAddresses:  []net.IP{net.IPv4(127, 0, 0, 1)},
	}
	derBytes, err = x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return derBytes, key
}

func testTLSCert(t *testing.T) tls.Certificate {
	t.Helper()
	der, key := testCertDER(t)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

// writeTestCertFiles 把自签证书写成 PEM 文件对，返回 (certPath, keyPath)。
func writeTestCertFiles(t *testing.T) (string, string) {
	t.Helper()
	der, key := testCertDER(t)
	dir := t.TempDir()
	certPath := filepath.Join(dir, "cert.pem")
	keyPath := filepath.Join(dir, "key.pem")
	certOut, err := os.Create(certPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := pem.Encode(certOut, &pem.Block{Type: "CERTIFICATE", Bytes: der}); err != nil {
		t.Fatal(err)
	}
	certOut.Close()
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	keyOut, err := os.Create(keyPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := pem.Encode(keyOut, &pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER}); err != nil {
		t.Fatal(err)
	}
	keyOut.Close()
	return certPath, keyPath
}

// startH3TestServer 在 127.0.0.1 随机 UDP 端口上起真 http3.Server
// （httptest 式），返回 h3 专用 http.Client 与 base URL。
func startH3TestServer(t *testing.T, a *app) (*http.Client, string) {
	t.Helper()
	udpConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	srv := &http3.Server{
		Handler:   a.h3Handler(),
		TLSConfig: http3.ConfigureTLSConfig(&tls.Config{Certificates: []tls.Certificate{testTLSCert(t)}}),
	}
	go srv.Serve(udpConn) //nolint:errcheck — Close 后返回错误属预期
	tr := &http3.Transport{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true}, // 自签测试证书
	}
	t.Cleanup(func() {
		tr.Close()
		srv.Close()
		udpConn.Close()
	})
	client := &http.Client{Transport: tr, Timeout: 30 * time.Second}
	return client, "https://" + udpConn.LocalAddr().String()
}

// ---------- h3 实测：/echo 与 /stream 经真实 QUIC 协商 ----------

// /echo 经 h3：响应必须带 X-Aneb-Proto 的 h3 证据（服务端视角 r.Proto
// 为 HTTP/3.0 且经 h3.Server 处理），且既有语义（时戳、observed、版本头）
// 原样保留。
func TestH3EchoNegotiatesHTTP3(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir(), h3Enabled: true}
	client, base := startH3TestServer(t, a)

	resp, err := client.Post(base+"/api/v1/echo", "application/octet-stream", strings.NewReader("ping"))
	if err != nil {
		t.Fatalf("h3 POST /echo: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d, want 200", resp.StatusCode)
	}
	if got := resp.Header.Get("X-Aneb-Proto"); got != "HTTP/3.0;via=h3-server" {
		t.Fatalf("X-Aneb-Proto = %q, want %q", got, "HTTP/3.0;via=h3-server")
	}
	if got := resp.Header.Get("X-Aneb-Server"); got != serverVersion {
		t.Fatalf("X-Aneb-Server = %q, want %q", got, serverVersion)
	}
	// 客户端视角的协商证据：响应 Proto 也必须是 HTTP/3.0。
	if resp.Proto != "HTTP/3.0" {
		t.Fatalf("client-side resp.Proto = %q, want HTTP/3.0", resp.Proto)
	}
	var e echoResp
	if err := json.NewDecoder(resp.Body).Decode(&e); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if e.T2Us < e.T1Us {
		t.Fatalf("t2_us %d < t1_us %d", e.T2Us, e.T1Us)
	}
	if e.AnchorWallUnixNs != anchorWallUnixNs {
		t.Fatalf("anchor mismatch: %d vs %d", e.AnchorWallUnixNs, anchorWallUnixNs)
	}
	if !strings.Contains(e.Observed, ":") {
		t.Fatalf("observed not ip:port: %q", e.Observed)
	}
}

// /stream 经 h3：SSE 流必须完整（prelude 注释帧 + 3 个 token event +
// summary event），且带 h3 协商证据头——证明 http.Flusher 语义在
// http3.Server 下同样成立。
func TestH3StreamComplete(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir(), h3Enabled: true}
	client, base := startH3TestServer(t, a)

	resp, err := client.Get(base + "/api/v1/stream?tokens=3&rate_tps=100&seed=7")
	if err != nil {
		t.Fatalf("h3 GET /stream: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d, want 200", resp.StatusCode)
	}
	if got := resp.Header.Get("X-Aneb-Proto"); got != "HTTP/3.0;via=h3-server" {
		t.Fatalf("X-Aneb-Proto = %q, want %q", got, "HTTP/3.0;via=h3-server")
	}
	if ct := resp.Header.Get("Content-Type"); ct != "text/event-stream" {
		t.Fatalf("Content-Type = %q", ct)
	}
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read stream: %v", err)
	}
	body := string(raw)
	if !strings.Contains(body, ": prelude {") {
		t.Fatalf("missing prelude comment frame in:\n%s", body)
	}
	if n := strings.Count(body, "event: token\n"); n != 3 {
		t.Fatalf("token events = %d, want 3\n%s", n, body)
	}
	for _, seq := range []string{`"seq":0`, `"seq":1`, `"seq":2`} {
		if !strings.Contains(body, seq) {
			t.Fatalf("missing %s in stream body", seq)
		}
	}
	if n := strings.Count(body, "event: summary\n"); n != 1 {
		t.Fatalf("summary events = %d, want 1", n)
	}
	if !strings.Contains(body, `"flush_return_us":[`) {
		t.Fatalf("summary missing flush_return_us array")
	}
}

// /serverinfo 经 h3：h3_enabled 必须如实上报 true。
func TestH3ServerInfoReportsH3Enabled(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir(), h3Enabled: true}
	client, base := startH3TestServer(t, a)

	resp, err := client.Get(base + "/api/v1/serverinfo")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var info serverInfo
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if !info.H3Enabled {
		t.Fatalf("h3_enabled = false, want true")
	}
}

// ---------- TCP 侧：Alt-Svc 广告与 proto 证据头 ----------

// -h3 开启时 TCP 响应必须带 Alt-Svc 广告与 via=tcp 的 proto 证据头；
// 既有 X-Aneb-Server 头不受影响。
func TestTCPHandlerAdvertisesAltSvcWhenH3Enabled(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir(), h3Enabled: true}
	altSvc, err := altSvcValue(":8443")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(a.tcpHandler(altSvc))
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/v1/serverinfo")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if got := resp.Header.Get("Alt-Svc"); got != `h3=":8443"; ma=86400` {
		t.Fatalf("Alt-Svc = %q", got)
	}
	if got := resp.Header.Get("X-Aneb-Proto"); got != "HTTP/1.1;via=tcp" {
		t.Fatalf("X-Aneb-Proto = %q, want HTTP/1.1;via=tcp", got)
	}
	if got := resp.Header.Get("X-Aneb-Server"); got != serverVersion {
		t.Fatalf("X-Aneb-Server = %q", got)
	}
	var info serverInfo
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		t.Fatal(err)
	}
	if !info.H3Enabled {
		t.Fatalf("h3_enabled = false, want true")
	}
}

// h3 关闭（altSvc 为空）时：绝不广告 Alt-Svc，serverinfo 上报
// h3_enabled=false，proto 证据头仍在（所有响应统一携带）。
func TestTCPHandlerNoAltSvcWhenH3Disabled(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.tcpHandler(""))
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/v1/serverinfo")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if got := resp.Header.Get("Alt-Svc"); got != "" {
		t.Fatalf("Alt-Svc = %q, want empty (h3 disabled must not advertise)", got)
	}
	if got := resp.Header.Get("X-Aneb-Proto"); got != "HTTP/1.1;via=tcp" {
		t.Fatalf("X-Aneb-Proto = %q", got)
	}
	var info serverInfo
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		t.Fatal(err)
	}
	if info.H3Enabled {
		t.Fatalf("h3_enabled = true, want false")
	}
}

// ---------- fail-closed：无证书的 -h3 必须拒绝启动 ----------

func TestValidateH3Prereqs(t *testing.T) {
	cases := []struct {
		name, cert, key string
		wantErr         bool
	}{
		{"both missing", "", "", true},
		{"key missing", "cert.pem", "", true},
		{"cert missing", "", "key.pem", true},
		{"both present", "cert.pem", "key.pem", false},
	}
	for _, c := range cases {
		err := validateH3Prereqs(c.cert, c.key)
		if (err != nil) != c.wantErr {
			t.Fatalf("%s: err = %v, wantErr = %v", c.name, err, c.wantErr)
		}
	}
}

// 子进程实测：无证书 -h3 启动必须以非零码退出并给出明确提示（不是
// 静默降级只起 TCP）。通过重新 exec 测试二进制触发 main()。
func TestH3WithoutCertRefusesToStart(t *testing.T) {
	if os.Getenv("ANEB_TEST_H3_NOCERT_HELPER") == "1" {
		os.Args = []string{"aneb-server", "-h3"}
		main() // 期望 log.Fatalf → os.Exit(1)，绝不返回
		t.Fatalf("main() returned, want fatal exit")
		return
	}
	cmd := exec.Command(os.Args[0], "-test.run", "^TestH3WithoutCertRefusesToStart$", "-test.v")
	cmd.Env = append(os.Environ(), "ANEB_TEST_H3_NOCERT_HELPER=1")
	out, err := cmd.CombinedOutput()
	if err == nil {
		t.Fatalf("subprocess exited 0, want non-zero (fail-closed)\noutput:\n%s", out)
	}
	if !strings.Contains(string(out), "-h3 requires both -tls-cert and -tls-key") {
		t.Fatalf("missing fail-closed message, output:\n%s", out)
	}
}

// ---------- 辅助函数单测 ----------

func TestAltSvcValue(t *testing.T) {
	got, err := altSvcValue(":8443")
	if err != nil || got != `h3=":8443"; ma=86400` {
		t.Fatalf("altSvcValue(:8443) = %q, %v", got, err)
	}
	got, err = altSvcValue("127.0.0.1:9000")
	if err != nil || got != `h3=":9000"; ma=86400` {
		t.Fatalf("altSvcValue(127.0.0.1:9000) = %q, %v", got, err)
	}
	if _, err := altSvcValue("no-port"); err == nil {
		t.Fatalf("altSvcValue(no-port) succeeded, want error")
	}
}

func TestValidateTLSFiles(t *testing.T) {
	certPath, keyPath := writeTestCertFiles(t)
	if err := validateTLSFiles(certPath, keyPath); err != nil {
		t.Fatalf("valid pair rejected: %v", err)
	}
	if err := validateTLSFiles(filepath.Join(t.TempDir(), "missing.pem"), keyPath); err == nil {
		t.Fatalf("missing cert accepted, want error")
	}
	if err := validateTLSFiles(certPath, filepath.Join(t.TempDir(), "missing.pem")); err == nil {
		t.Fatalf("missing key accepted, want error")
	}
	if err := validateTLSFiles(t.TempDir(), keyPath); err == nil {
		t.Fatalf("directory as cert accepted, want error")
	}
	// cert 当 key 用：成对加载必须失败。
	if err := validateTLSFiles(certPath, certPath); err == nil {
		t.Fatalf("mismatched pair accepted, want error")
	}
}
