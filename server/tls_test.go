// tls_test.go：SNI 双通道证书选择单测。验证 getCertificate 依 ClientHello
// 的 ServerName 正确分流（具名主机→默认证书；空/IP SNI→IP-SAN 证书；
// 未配 IP 证书时回退默认），以及 validateIPCertPair 的成对约束。
package main

import (
	"crypto/tls"
	"net"
	"net/http"
	"testing"
	"time"
)

// 用两张可区分的空壳证书（Leaf 携带一个哨兵字段无法直接比对，改用指针相等）。
func newSelector(withIP bool) (*certSelector, *tls.Certificate, *tls.Certificate) {
	def := tls.Certificate{}
	sel := &certSelector{defaultCert: def}
	var ip *tls.Certificate
	if withIP {
		ipc := tls.Certificate{}
		sel.ipCert = &ipc
		ip = &ipc
	}
	return sel, &sel.defaultCert, ip
}

func TestGetCertificate_SNIRouting(t *testing.T) {
	sel, def, ip := newSelector(true)

	cases := []struct {
		name string
		sni  string
		want *tls.Certificate
	}{
		{"named sslip hostname -> default(LE)", sslipHostname, def},
		{"other named host -> default", "example.com", def},
		{"empty SNI (bare-IP connect) -> IP-SAN", "", ip},
		{"IPv4 literal SNI -> IP-SAN", "120.79.148.0", ip},
		{"IPv6 literal SNI -> IP-SAN", "::1", ip},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := sel.getCertificate(&tls.ClientHelloInfo{ServerName: c.sni})
			if err != nil {
				t.Fatalf("getCertificate(%q): %v", c.sni, err)
			}
			if got != c.want {
				t.Errorf("getCertificate(%q) = %p, want %p", c.sni, got, c.want)
			}
		})
	}
}

func TestGetCertificate_FallbackWhenNoIPCert(t *testing.T) {
	sel, def, _ := newSelector(false) // 未配 IP 证书
	// bare-IP/空 SNI 应回退默认证书（fail-open）。
	for _, sni := range []string{"", "120.79.148.0"} {
		got, err := sel.getCertificate(&tls.ClientHelloInfo{ServerName: sni})
		if err != nil {
			t.Fatalf("getCertificate(%q): %v", sni, err)
		}
		if got != def {
			t.Errorf("getCertificate(%q) = %p, want default %p (fallback)", sni, got, def)
		}
	}
}

func TestValidateIPCertPair(t *testing.T) {
	// 两者皆空：合法（未启用 IP 分支）。
	if err := validateIPCertPair("", ""); err != nil {
		t.Errorf("both empty should be ok, got %v", err)
	}
	// 只给一个：非法。
	if err := validateIPCertPair("cert.pem", ""); err == nil {
		t.Errorf("cert without key should error")
	}
	if err := validateIPCertPair("", "key.pem"); err == nil {
		t.Errorf("key without cert should error")
	}
}

// TestTLSPinsHTTP11 验证测量端点在 TLS-over-TCP 上显式置空 TLSNextProto，
// 即使客户端主动提供 h2 ALPN 也严格协商到 HTTP/1.1，避免 h2 流控污染上行测量。
func TestTLSPinsHTTP11(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	srv := newTCPServer(a, "127.0.0.1:0", "", &tls.Config{
		Certificates: []tls.Certificate{testTLSCert(t)},
	})

	if srv.TLSNextProto == nil {
		t.Fatal("TLSNextProto 为 nil 会沿用 Go 默认启用 h2；必须为非 nil 空映射")
	}
	if n := len(srv.TLSNextProto); n != 0 {
		t.Fatalf("TLSNextProto 有 %d 项，期望 0", n)
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	go func() { _ = srv.ServeTLS(ln, "", "") }()
	t.Cleanup(func() { _ = srv.Close() })

	client := &http.Client{
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			ForceAttemptHTTP2: true,
			TLSClientConfig: &tls.Config{
				InsecureSkipVerify: true,
				NextProtos:         []string{"h2", "http/1.1"},
			},
		},
	}
	resp, err := client.Get("https://" + ln.Addr().String() + "/api/v1/serverinfo")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status=%d, want 200", resp.StatusCode)
	}
	if resp.Proto != "HTTP/1.1" {
		t.Fatalf("协商到 %q，期望 HTTP/1.1", resp.Proto)
	}
	if got := resp.TLS.NegotiatedProtocol; got != "" && got != "http/1.1" {
		t.Fatalf("ALPN 协商结果 %q，期望空或 http/1.1", got)
	}
}
