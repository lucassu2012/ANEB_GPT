package main

import (
	"fmt"
	"net/http"
	"strconv"
)

const (
	downloadDefaultBytes   int64 = 64 << 20
	downloadMaxBytes       int64 = 1 << 30
	downloadDefaultChunkKB       = 256
	downloadMaxChunkKB           = 1024
)

// handleDownload GET /api/v1/download?bytes=&chunk_kb=：基本网络性能模式的大对象下载端点。
//
// 响应体按固定块流式生成，不把大对象整体放入内存；Content-Length 明确，禁缓存/压缩。
// 客户端可在 profile 规定的持续时间结束时取消请求，服务端随 request context 退出。
func (a *app) handleDownload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	total, err := positiveInt64Query(r, "bytes", downloadDefaultBytes, downloadMaxBytes)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	chunkKB, err := positiveIntQuery(r, "chunk_kb", downloadDefaultChunkKB, downloadMaxChunkKB)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	chunk := make([]byte, chunkKB<<10)
	// 非全零的固定模式便于抓包辨识；HTTP 服务端未启用内容压缩，wire 字节数等于 Content-Length。
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
