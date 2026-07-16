// tls.go：SNI 双通道证书选择（阶段 3）。设计依据见 MEMORY『研究背景』：
// 电信 NR-SA 对 sslip.io 主机名注入 SNI-keyed TLS RST，bare-IP 路径 TLS 可
// 完成仅缺 IP-SAN。本文件让同一监听端口按 ClientHello 的 SNI 分流证书：
//
//   - ServerName == "120-79-148-0.sslip.io"（具名主机）→ 默认（LE 公共）证书，
//     供 WiFi 与 Cronet QUIC 的 known-root 校验；
//   - ServerName 为空（bare-IP 连接不发 SNI）或为 IP 字面量 → 自签 IP-SAN 证书，
//     供蜂窝 bare-IP 通道（含 IP:120.79.148.0）；
//   - 其它具名主机 → 回退默认证书。
//
// fail-open 边界：未配置 -tls-cert-ip/-tls-key-ip 时 bare-IP 分支回退默认证书
// 并在启动时日志告警（默认证书通常无 IP-SAN，蜂窝 bare-IP 校验会失败）——
// 这样本机（默认分支）go vet/test 不因缺 IP 证书而红。
package main

import (
	"crypto/tls"
	"fmt"
	"log"
	"net"
)

// sslipHostname 是 E-01 的 LE 公共证书对应主机名（sslip.io 把点分 IP 编进域名）。
const sslipHostname = "120-79-148-0.sslip.io"

// certSelector 持有两套证书并依 SNI 分流。ipCert 为 nil 表示未配置 IP-SAN 证书，
// bare-IP 分支回退 defaultCert。
type certSelector struct {
	defaultCert tls.Certificate  // -tls-cert/-tls-key：具名主机（sslip.io）+ WiFi/QUIC
	ipCert      *tls.Certificate // -tls-cert-ip/-tls-key-ip：自签 IP-SAN（bare-IP 蜂窝）；nil=回退
}

// getCertificate 是 tls.Config.GetCertificate 回调。TCP 与 h3（QUIC）共用同一
// 回调——ConfigureTLSConfig 会克隆并保留 GetCertificate。
func (s *certSelector) getCertificate(hello *tls.ClientHelloInfo) (*tls.Certificate, error) {
	name := hello.ServerName
	// bare-IP 分支：无 SNI（蜂窝 bare-IP 连接）或 SNI 是 IP 字面量 → IP-SAN 证书。
	if name == "" || net.ParseIP(name) != nil {
		if s.ipCert != nil {
			return s.ipCert, nil
		}
		return &s.defaultCert, nil
	}
	// 具名主机（sslip.io 及任何其它域名）→ 默认（LE 公共）证书。
	return &s.defaultCert, nil
}

// newTLSConfig 加载默认证书（必需）与可选 IP-SAN 证书，返回带 SNI 分流
// GetCertificate 回调的 tls.Config。调用前提：certFile/keyFile 已通过
// validateTLSFiles 校验；ipCertFile/ipKeyFile 若给出则须成对。
func newTLSConfig(certFile, keyFile, ipCertFile, ipKeyFile string) (*tls.Config, error) {
	def, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return nil, fmt.Errorf("load default tls cert/key: %w", err)
	}
	sel := &certSelector{defaultCert: def}
	if ipCertFile != "" && ipKeyFile != "" {
		ipc, err := tls.LoadX509KeyPair(ipCertFile, ipKeyFile)
		if err != nil {
			return nil, fmt.Errorf("load IP-SAN tls cert/key (-tls-cert-ip %q, -tls-key-ip %q): %w", ipCertFile, ipKeyFile, err)
		}
		sel.ipCert = &ipc
		log.Printf("tls: SNI dual-path enabled — named SNI (%s) → default cert, bare-IP/empty SNI → IP-SAN cert", sslipHostname)
	} else {
		log.Printf("WARNING: no -tls-cert-ip/-tls-key-ip given; bare-IP TLS clients receive the default cert (likely no IP-SAN) — cellular bare-IP path may fail cert validation")
	}
	return &tls.Config{GetCertificate: sel.getCertificate}, nil
}

// validateIPCertPair fail-closed：-tls-cert-ip 与 -tls-key-ip 必须同时给出
// 或同时省略，且给出时须能成对加载。
func validateIPCertPair(ipCertFile, ipKeyFile string) error {
	if ipCertFile == "" && ipKeyFile == "" {
		return nil
	}
	if ipCertFile == "" || ipKeyFile == "" {
		return fmt.Errorf("-tls-cert-ip and -tls-key-ip must be given together")
	}
	return validateTLSFiles(ipCertFile, ipKeyFile)
}
