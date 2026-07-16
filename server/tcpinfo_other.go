//go:build !linux

package main

import "syscall"

// tcpTotalRetrans 非 Linux 平台无 TCP_INFO getsockopt：恒 n/a。
// （开发机 Windows/macOS 跑 go test 走本分支；取证部署环境为 Linux。）
func tcpTotalRetrans(_ syscall.RawConn) (uint32, bool) { return 0, false }
