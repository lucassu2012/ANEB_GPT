package main

import "time"

// R-24: 所有对外时间戳一律是"进程启动单调锚点的微秒差"。
// procStart 在进程启动时捕获一次，其内部携带单调时钟读数；
// time.Since(procStart) 只用单调分量，墙钟步进（NTP makestep、宿主机校时）
// 无法影响任何逐事件时间戳。
// 墙钟仅以 anchorWallUnixNs 的形式在 /echo 响应与 /stream prelude 中
// 各附带一次，供日志离线映射；任何逐事件字段禁止使用墙钟。
var (
	procStart        = time.Now()
	anchorWallUnixNs = procStart.UnixNano()
)

// nowMicros 返回自进程启动单调锚点起经过的微秒数。
func nowMicros() int64 {
	return time.Since(procStart).Microseconds()
}
