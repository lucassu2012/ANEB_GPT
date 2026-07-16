//go:build linux

package main

import (
	"syscall"
	"unsafe"
)

// linuxTCPInfo 镜像 Linux struct tcp_info（include/uapi/linux/tcp.h）从头到
// tcpi_total_retrans 的前缀布局（2.6 内核以来该前缀稳定；total_retrans 之后
// 的新增字段本采样不需要）。内核 getsockopt(TCP_INFO) 复制
// min(optlen, sizeof(kernel tcp_info)) 字节并回写实际长度，取前缀是官方
// 兼容用法（ss/iproute2 同款）——手写 syscall 而非引 golang.org/x/sys，
// 维持 server 仅标准库零第三方依赖的现状（main.go 顶注）。
type linuxTCPInfo struct {
	State        uint8
	CaState      uint8
	Retransmits  uint8
	Probes       uint8
	Backoff      uint8
	Options      uint8
	WscalePack   uint8 // snd_wscale:4, rcv_wscale:4
	RateLimPack  uint8 // delivery_rate_app_limited:1, fastopen_client_fail:2
	Rto          uint32
	Ato          uint32
	SndMss       uint32
	RcvMss       uint32
	Unacked      uint32
	Sacked       uint32
	Lost         uint32
	Retrans      uint32
	Fackets      uint32
	LastDataSent uint32
	LastAckSent  uint32
	LastDataRecv uint32
	LastAckRecv  uint32
	Pmtu         uint32
	RcvSsthresh  uint32
	Rtt          uint32
	Rttvar       uint32
	SndSsthresh  uint32
	SndCwnd      uint32
	Advmss       uint32
	Reordering   uint32
	RcvRtt       uint32
	RcvSpace     uint32
	TotalRetrans uint32
}

// tcpTotalRetrans 对已连接 TCP socket 读 TCP_INFO 并取 tcpi_total_retrans
// （连接生命周期累计重传段数）。任何失败（errno、内核回写长度不足以覆盖
// TotalRetrans）→ ok=false，调用方按 n/a 处理。
func tcpTotalRetrans(raw syscall.RawConn) (uint32, bool) {
	var info linuxTCPInfo
	var ok bool
	ctrlErr := raw.Control(func(fd uintptr) {
		optlen := uint32(unsafe.Sizeof(info))
		_, _, errno := syscall.Syscall6(
			syscall.SYS_GETSOCKOPT,
			fd,
			uintptr(syscall.IPPROTO_TCP),
			uintptr(syscall.TCP_INFO),
			uintptr(unsafe.Pointer(&info)),
			uintptr(unsafe.Pointer(&optlen)),
			0,
		)
		// 极老内核的 tcp_info 若短于本前缀，TotalRetrans 未被内核写入——
		// 判 n/a 而非读到零值当真（不编造数值）。
		ok = errno == 0 && uintptr(optlen) >= unsafe.Sizeof(info)
	})
	if ctrlErr != nil {
		return 0, false
	}
	return info.TotalRetrans, ok
}
