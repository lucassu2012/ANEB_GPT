// h3.go：阶段 2 HTTP/3（quic-go）支持。设计依据：《ANEB Probe 开发设计文档》
// §6/§8 阶段 2 与 DECISION_LOG D-10/D-17。
//
// 核心约束（红队项）：**配置启用 QUIC ≠ 协商到 h3**。服务端一侧的对账证据
// 是 X-Aneb-Proto 头（r.Proto + 处理栈标记），客户端一侧逐样本记录
// negotiatedProtocol；两侧都留痕，A/B 分组才可信。
//
// fail-closed：-h3 强制 TLS——无 -tls-cert/-tls-key 时直接启动失败，
// 不允许无证书的 h3（QUIC 本身 TLS-only，静默降级为"只起 TCP"会让运维
// 误以为 h3 已就绪）。
package main

import (
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"

	"github.com/quic-go/quic-go/http3"
)

// 处理栈标记：X-Aneb-Proto 的 via= 取值。tcp 侧含 HTTP/1.1 与（若启用）h2。
const (
	protoViaTCP = "tcp"
	protoViaH3  = "h3-server"
)

// withProtoEvidence 为所有响应附加 X-Aneb-Proto 头：
// 值 = 服务端视角的 r.Proto + ";via=" + 处理栈标记。
// 例：`HTTP/3.0;via=h3-server`、`HTTP/1.1;via=tcp`。
func withProtoEvidence(next http.Handler, via string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Aneb-Proto", r.Proto+";via="+via)
		next.ServeHTTP(w, r)
	})
}

// tcpHandler 组装 TCP 侧完整 handler：既有路由树 + X-Aneb-Proto 证据头 +
// （仅 -h3 开启时 altSvc 非空）Alt-Svc 头广告同端口 UDP 上的 h3。
// 除新增响应头外，TCP 侧行为与阶段 0/1 完全一致——既有端点语义、时间戳
// 口径、超时策略均不受影响。
func (a *app) tcpHandler(altSvc string) http.Handler {
	next := withProtoEvidence(a.routes(), protoViaTCP)
	if altSvc == "" {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Alt-Svc", altSvc)
		next.ServeHTTP(w, r)
	})
}

// h3Handler 组装 h3 侧完整 handler：复用同一路由树与中间件，仅 via 标记
// 不同（证明该响应确实经 http3.Server 处理，而非 TCP 侧伪装）。
func (a *app) h3Handler() http.Handler {
	return withProtoEvidence(a.routes(), protoViaH3)
}

// newH3Server 构造与 TCP 侧同端口（UDP）的 http3.Server。
// 刻意不设 IdleTimeout 之类额外限制：与 TCP 侧一样，流式端点（S2 流 ~90s）
// 不允许被整连接超时截断，断开检测交给 handler 内的 r.Context()。
//
// tlsConf 复用 TCP 侧同一份带 SNI 分流 GetCertificate 的 tls.Config——QUIC 的
// 证书选择走同一回调（http3.ConfigureTLSConfig 克隆时保留 GetCertificate），
// 因此 h3 的 bare-IP/具名 SNI 分流与 TCP 完全一致。
func (a *app) newH3Server(addr string, tlsConf *tls.Config) *http3.Server {
	return &http3.Server{
		Addr:      addr,
		Handler:   a.h3Handler(),
		TLSConfig: tlsConf,
	}
}

// altSvcValue 由监听地址推导 TCP 响应的 Alt-Svc 头值（h3 与 TCP 同端口，
// 仅传输层换 UDP）。ma=86400 与 quic-go 默认口径一致。
func altSvcValue(addr string) (string, error) {
	_, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "", fmt.Errorf("cannot derive Alt-Svc port from addr %q: %w", addr, err)
	}
	if port == "" {
		return "", fmt.Errorf("cannot derive Alt-Svc port from addr %q: empty port", addr)
	}
	return fmt.Sprintf(`h3=":%s"; ma=86400`, port), nil
}

// validateH3Prereqs fail-closed 前置校验：-h3 必须同时给出 -tls-cert 与
// -tls-key。h3 是 TLS-only 协议，无证书时拒绝启动而不是静默只起 TCP。
func validateH3Prereqs(tlsCert, tlsKey string) error {
	if tlsCert == "" || tlsKey == "" {
		return errors.New("-h3 requires both -tls-cert and -tls-key (HTTP/3 is TLS-only); refusing to start without certificates (fail-closed)")
	}
	return nil
}

// validateTLSFiles 证书路径校验：两个路径都必须是存在的普通文件，且
// cert/key 能成对加载（提前在启动时暴露配错的路径/不匹配的密钥对，
// 而不是等到首个 TLS 握手才失败）。
func validateTLSFiles(tlsCert, tlsKey string) error {
	for name, p := range map[string]string{"-tls-cert": tlsCert, "-tls-key": tlsKey} {
		st, err := os.Stat(p)
		if err != nil {
			return fmt.Errorf("%s %q: %w", name, p, err)
		}
		if st.IsDir() {
			return fmt.Errorf("%s %q is a directory, want a PEM file", name, p)
		}
	}
	if _, err := tls.LoadX509KeyPair(tlsCert, tlsKey); err != nil {
		return fmt.Errorf("tls cert/key pair invalid (-tls-cert %q, -tls-key %q): %w", tlsCert, tlsKey, err)
	}
	return nil
}
