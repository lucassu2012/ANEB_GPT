package main

import (
	"net/http"
	"strconv"
)

// handleEcho POST /api/v1/echo（R-23/R-32）：4 时间戳时钟同步的服务端一侧。
//
// handler 极薄：无日志、无反射（不走 encoding/json）、响应缓冲预分配，
// 把栈内驻留压到最低，使 t2-t1 可作为驻留监控指标随样本返回。
// t1 = 收到请求（handler 进入）时刻；t2 = 发送前时刻；均为进程锚点微秒差（R-24）。
// observed 回显客户端源 IP:port，供路径对账（R-01/R-31）。
// anchor_wall_unix_ns 是墙钟仅有的一次出现，只用于日志离线映射。
func (a *app) handleEcho(w http.ResponseWriter, r *http.Request) {
	t1 := nowMicros()
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	// 请求体 <100B 任意内容：读干净（保证 t1..t2 覆盖整个请求接收）后丢弃。
	var buf [128]byte
	var total int
	for {
		n, err := r.Body.Read(buf[:])
		total += n
		// 先校验已读字节数，再看 err：io.Reader 契约允许单次 Read 同时返回
		// n>0 与非 nil err（如 io.EOF），若先 break 会漏掉最后一次读入的超限
		// （"EOF 单独作为一次 0 字节读返回"只是当前实现习惯，不是接口保证）。
		if total > 100 {
			http.Error(w, "body too large", http.StatusRequestEntityTooLarge)
			return
		}
		if err != nil {
			break
		}
	}

	// 预分配响应缓冲，手工拼 JSON（零反射）。
	out := make([]byte, 0, 160)
	out = append(out, `{"t1_us":`...)
	out = strconv.AppendInt(out, t1, 10)
	out = append(out, `,"anchor_wall_unix_ns":`...)
	out = strconv.AppendInt(out, anchorWallUnixNs, 10)
	out = append(out, `,"observed":`...)
	// strconv.AppendQuote 做 JSON 兼容转义：RemoteAddr 正常不含需转义字符，
	// 但手拼 JSON 不转义是脆弱模式；AppendQuote 零反射，handler 保持极薄。
	out = strconv.AppendQuote(out, r.RemoteAddr)
	out = append(out, `,"t2_us":`...)
	// t2 尽量贴近实际写出时刻：最后取值。
	out = strconv.AppendInt(out, nowMicros(), 10)
	out = append(out, '}')

	h := w.Header()
	h.Set("Content-Type", "application/json")
	h.Set("Content-Length", strconv.Itoa(len(out)))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(out)
}
