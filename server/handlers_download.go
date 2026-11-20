package main

import (
	"fmt"
	"net/http"
	"strconv"
)

// 下行端点契约与已部署共享服务器（Codex v0.2.0 引入）一致，Claude 树补齐以免
// 本树被重新部署时回退掉线上 /download（会同时坑掉基本性能模式的下行测速）。
const (
	downloadDefaultBytes   int64 = 64 << 20 // 64MiB
	downloadMaxBytes       int64 = 1 << 30  // 1GiB 上限（有界单请求工作量，防单流长占 goroutine/CPU）
	downloadDefaultChunkKB       = 256
	downloadMaxChunkKB           = 1024
)

// handleDownload GET /api/v1/download?bytes=&chunk_kb=：基本网络性能模式的大对象下载端点。
//
// 响应体按固定块流式生成，不把大对象整体放入内存；Content-Length 明确，禁缓存/压缩
// （identity）。**不做任何限速**（无 sleep/限流）——客户端据此测真实、随网络波动的下行吞吐。
// 客户端在 profile 规定的持续时间结束时取消请求，服务端随 request context 退出。
func (a *app) handleDownload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// 缺省字节/块可来自冻结 profile 的 download_burst 相位（§2.4/§3.1，单一事实源，
	// 与 /stream 的 profile 解析对齐）；query bytes/chunk_kb 仍可覆盖。
	defBytes, defChunkKB, err := a.downloadDefaultsFromProfile(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	total, err := positiveInt64Query(r, "bytes", defBytes, downloadMaxBytes)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	chunkKB, err := positiveIntQuery(r, "chunk_kb", defChunkKB, downloadMaxChunkKB)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	chunk := make([]byte, chunkKB<<10)
	// 非全零的固定模式便于抓包辨识；服务端未启用内容压缩，wire 字节数等于 Content-Length。
	for i := range chunk {
		chunk[i] = byte((i*31 + 17) & 0xff)
	}

	h := w.Header()
	h.Set("Content-Type", "application/octet-stream")
	h.Set("Content-Length", strconv.FormatInt(total, 10))
	h.Set("Cache-Control", "no-store, no-cache, must-revalidate")
	h.Set("Content-Encoding", "identity")
	h.Set("X-Aneb-Download-Bytes", strconv.FormatInt(total, 10))
	w.WriteHeader(http.StatusOK)

	remaining := total
	for remaining > 0 {
		select {
		case <-r.Context().Done():
			return
		default:
		}
		n := int64(len(chunk))
		if remaining < n {
			n = remaining
		}
		written, writeErr := w.Write(chunk[:int(n)])
		if writeErr != nil {
			return
		}
		if written != int(n) {
			return
		}
		remaining -= n
	}
}

// downloadDefaultsFromProfile 解析 profile=&phase=：有 profile 时取该 profile 第 phase 个
// download_burst 相位的 bytes/chunk_kb 作缺省（单一事实源；query 仍可覆盖）；无 profile 时用全局缺省。
// download_burst 声明的 bytes/chunk_kb 越界（超 /download 上限）直接报错，避免运行期才 400。
func (a *app) downloadDefaultsFromProfile(r *http.Request) (int64, int, error) {
	q := r.URL.Query()
	pid := q.Get("profile")
	if pid == "" {
		return downloadDefaultBytes, downloadDefaultChunkKB, nil
	}
	p, ok := a.profiles[pid]
	if !ok {
		return 0, 0, fmt.Errorf("unknown profile: %s", pid)
	}
	phaseIdx := 0
	if s := q.Get("phase"); s != "" {
		v, err := strconv.Atoi(s)
		if err != nil || v < 0 {
			return 0, 0, fmt.Errorf("invalid phase: %s", s)
		}
		phaseIdx = v
	}
	ph, err := p.downloadBurstPhase(phaseIdx)
	if err != nil {
		return 0, 0, err
	}
	bytes := downloadDefaultBytes
	if ph.Bytes > 0 {
		if ph.Bytes > downloadMaxBytes {
			return 0, 0, fmt.Errorf("download_burst bytes %d exceeds max %d", ph.Bytes, downloadMaxBytes)
		}
		bytes = ph.Bytes
	}
	chunkKB := downloadDefaultChunkKB
	if ph.ChunkKB > 0 {
		if ph.ChunkKB > downloadMaxChunkKB {
			return 0, 0, fmt.Errorf("download_burst chunk_kb %d exceeds max %d", ph.ChunkKB, downloadMaxChunkKB)
		}
		chunkKB = ph.ChunkKB
	}
	return bytes, chunkKB, nil
}

// positiveInt64Query 读取正整数查询参数，缺省用 fallback，越界（(0,max] 之外）报错。
func positiveInt64Query(r *http.Request, key string, fallback, max int64) (int64, error) {
	raw := r.URL.Query().Get(key)
	if raw == "" {
		return fallback, nil
	}
	v, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || v <= 0 || v > max {
		return 0, fmt.Errorf("%s must be in [1,%d]", key, max)
	}
	return v, nil
}

func positiveIntQuery(r *http.Request, key string, fallback, max int) (int, error) {
	v, err := positiveInt64Query(r, key, int64(fallback), int64(max))
	return int(v), err
}
