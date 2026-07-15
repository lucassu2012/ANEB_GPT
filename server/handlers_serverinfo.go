package main

import (
	"encoding/json"
	"net/http"
	"os"
	"runtime"
	"strings"
)

// serverInfo 是 GET /api/v1/serverinfo 的响应体：运行元数据快照，客户端
// 把它并入 TestRun 的"服务端环境快照"（设计文档 §7：sysctl 关键项、拥塞
// 算法等须随每次运行存档，保证结果可复核）。
type serverInfo struct {
	Version string `json:"version"`
	// 单调锚点 → 墙钟映射（R-24）：srv_ts_us 是"此刻"的进程锚点微秒差，
	// anchor_wall_unix_ns 是锚点对应的墙钟。两者联立即可把任何 srv_ts
	// 离线映射回墙钟，而逐事件时间戳本身永不携带墙钟。
	SrvTsUs          int64 `json:"srv_ts_us"`
	AnchorWallUnixNs int64 `json:"anchor_wall_unix_ns"`
	UptimeS          int64 `json:"uptime_s"`

	Goos   string `json:"goos"`
	Goarch string `json:"goarch"`

	// H3Enabled：服务端是否以 -h3 启动（配置视角）。注意这只说明"h3 已
	// 启用"，不证明任何一次请求协商到了 h3——逐响应协商证据看 X-Aneb-Proto
	// 头与客户端 negotiatedProtocol（红队项：QUIC 启用 ≠ 协商 h3）。
	H3Enabled bool `json:"h3_enabled"`

	// Linux 下尽力读 /proc（读不到或非 Linux 一律 "n/a"，不猜不编）：
	//   tcp_slow_start_after_idle 期望钉死为 "0"（R-18：防停顿后 cwnd 重置
	//   污染 burst 段），congestion_control 期望固定 "cubic"（设计 §6）。
	//   这里只如实上报读数，是否达标由客户端/离线分析比对基线。
	TCPSlowStartAfterIdle string `json:"tcp_slow_start_after_idle"`
	CongestionControl     string `json:"congestion_control"`
}

// readProcValue 读取单值 /proc 文件并去空白；任何失败返回 "n/a"。
func readProcValue(path string) string {
	if runtime.GOOS != "linux" {
		return "n/a"
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return "n/a"
	}
	v := strings.TrimSpace(string(b))
	if v == "" {
		return "n/a"
	}
	return v
}

// handleServerInfo GET /api/v1/serverinfo：运行元数据端点。
func (a *app) handleServerInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	now := nowMicros()
	info := serverInfo{
		Version:               serverVersion,
		SrvTsUs:               now,
		AnchorWallUnixNs:      anchorWallUnixNs,
		UptimeS:               now / 1_000_000,
		Goos:                  runtime.GOOS,
		Goarch:                runtime.GOARCH,
		H3Enabled:             a.h3Enabled,
		TCPSlowStartAfterIdle: readProcValue("/proc/sys/net/ipv4/tcp_slow_start_after_idle"),
		CongestionControl:     readProcValue("/proc/sys/net/ipv4/tcp_congestion_control"),
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(info); err != nil {
		return // 客户端断开，无可挽回
	}
}
