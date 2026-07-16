// P3-C05：/stream 结束时的 TCP_INFO 采样（设计文档 §6 遗留条款落地一半：
// 本文件只做"流末尾一次性读 tcpi_total_retrans"，每秒 notsent 采样仍留待后续）。
//
// 用途：netem 100ms/1% 取证发现丢包重传造成的到达批化与 nginx proxy_buffering
// 的批化签名同形（evidence/phase3/netem_experiments_20260713.md 断言 3 误报），
// 客户端 BufferingDetector 需要"重传共变量"区分 RETRANS_SUSPECT 与
// MIDDLEBOX_SUSPECT。服务端在 summary event 里附 retrans_total（additive 字段）。
//
// 平台边界（fail-open 到 n/a，绝不编造数值，R-10 同款纪律）：
//   - 仅 Linux 有 TCP_INFO getsockopt（tcpTotalRetrans 的 linux 实现见
//     tcpinfo_linux.go；其余平台 tcpinfo_other.go 恒返回 ok=false）；
//   - h3/QUIC 无底层 TCP 连接，ctx 里取不到 conn → n/a；
//   - httptest / 未设 ConnContext 的 Server → n/a（单测两分支均覆盖）。
package main

import (
	"context"
	"crypto/tls"
	"net"
	"net/http"
	"syscall"
)

// connCtxKey 是 http.Server.ConnContext 存放底层 net.Conn 的 context key。
type connCtxKey struct{}

// connContext 供 http.Server.ConnContext 使用：把 accept 到的连接塞进每请求
// context，使 handler 能在流结束时对同一条连接做 TCP_INFO 采样。
// 注意 TLS 部署下这里拿到的是 *tls.Conn（ServeTLS 先包 tls.NewListener），
// connTotalRetrans 里再 NetConn() 解包。
func connContext(ctx context.Context, c net.Conn) context.Context {
	return context.WithValue(ctx, connCtxKey{}, c)
}

// connTotalRetrans 返回本请求底层 TCP 连接的 tcpi_total_retrans。
// ok=false 表示 n/a（非 Linux、h3/QUIC、无 ConnContext、非 TCP 连接、
// syscall 失败）——调用方按"无共变量数据"处理，不得写 0 顶替。
func connTotalRetrans(r *http.Request) (uint32, bool) {
	c, _ := r.Context().Value(connCtxKey{}).(net.Conn)
	if c == nil {
		return 0, false
	}
	if tc, isTLS := c.(*tls.Conn); isTLS {
		c = tc.NetConn()
	}
	sc, isSyscallConn := c.(syscall.Conn)
	if !isSyscallConn {
		return 0, false
	}
	raw, err := sc.SyscallConn()
	if err != nil {
		return 0, false
	}
	return tcpTotalRetrans(raw)
}
