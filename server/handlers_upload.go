package main

import (
	"encoding/json"
	"errors"
	"io"
	"math/rand"
	"net/http"
	"strconv"
	"time"
)

const uploadChunkSize = 64 * 1024

// uploadMaxBytes 是 /upload 与 /toolloop 请求体上限（与 /results 的
// MaxBytesReader 防护级别一致）：S3 场景单次上传 1MB，64MB 留足余量；
// 无上限的 body 会让 chunkUs 无界增长/读循环长期占用 goroutine，
// 由此产生的 CPU/内存压力还会污染同机其它测量会话的 srv_ts 采样。
const uploadMaxBytes = 64 << 20 // 64MB

// uploadResponse 是 /api/v1/upload 的响应体。
// 逐块到达时刻序列是上行节奏的权威序列（R-07）；客户端 writeTo 本地
// 写序列仅作辅助诊断（claim scope = 写入本地协议栈）。
type uploadResponse struct {
	Bytes       int64   `json:"bytes"`
	RecvStartUs int64   `json:"recv_start_us"`
	RecvEndUs   int64   `json:"recv_end_us"`
	ChunkUs     []int64 `json:"chunk_us"`
	Observed    string  `json:"observed"`
}

// handleUpload POST /api/v1/upload（R-07）：上行突发汇。
// 按 64KB 块读 body，记录逐块到达时刻，数据丢弃；**读完 body 之后**才写
// 响应——客户端以"收到本响应头"为 U1 计时终点（服务端已确认收完），
// 单端可测、不依赖时钟 offset，杜绝把写入本机 socket buffer 测成假吞吐。
func (a *app) handleUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	recvStart := nowMicros()
	body := http.MaxBytesReader(w, r.Body, uploadMaxBytes)
	buf := make([]byte, uploadChunkSize)
	var total int64
	chunkUs := make([]int64, 0, 64)
	for {
		n, err := body.Read(buf)
		if n > 0 {
			total += int64(n)
			chunkUs = append(chunkUs, nowMicros())
		}
		if err != nil {
			var mbe *http.MaxBytesError
			if errors.As(err, &mbe) {
				http.Error(w, "body too large", http.StatusRequestEntityTooLarge)
				return
			}
			if errors.Is(err, io.EOF) {
				break
			}
			// 只有 EOF 才证明请求体完整读完。连接截断或其它读取错误不能返回
			// 2xx，否则客户端会把未完整送达的字节误记为成功上传样本。
			http.Error(w, "body unreadable", http.StatusBadRequest)
			return
		}
	}
	recvEnd := nowMicros()

	resp := uploadResponse{
		Bytes:       total,
		RecvStartUs: recvStart,
		RecvEndUs:   recvEnd,
		ChunkUs:     chunkUs,
		Observed:    r.RemoteAddr,
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(&resp)
}

// handleToolLoop POST /api/v1/toolloop?proc_ms=200&down_bytes=2048：
// 工具循环回显。读完 body 记 t_recv_us，用**绝对 deadline** 等待 proc_ms
// （time.Until(deadline)，不做相对 sleep 累加），响应头带
// X-Aneb-Trecv-Us / X-Aneb-Tsend-Us，body 为 down_bytes 字节数据。
func (a *app) handleToolLoop(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	q := r.URL.Query()
	procMs := 200
	if s := q.Get("proc_ms"); s != "" {
		v, err := strconv.Atoi(s)
		if err != nil || v < 0 || v > 60000 {
			http.Error(w, "invalid proc_ms", http.StatusBadRequest)
			return
		}
		procMs = v
	}
	downBytes := int64(2048)
	if s := q.Get("down_bytes"); s != "" {
		v, err := strconv.ParseInt(s, 10, 64)
		if err != nil || v < 0 || v > 16<<20 {
			http.Error(w, "invalid down_bytes", http.StatusBadRequest)
			return
		}
		downBytes = v
	}

	// 读完 body（丢弃）后打 t_recv；同样套 64MB 上限防资源耗尽。
	reqBody := http.MaxBytesReader(w, r.Body, uploadMaxBytes)
	buf := make([]byte, uploadChunkSize)
	for {
		_, err := reqBody.Read(buf)
		if err != nil {
			var mbe *http.MaxBytesError
			if errors.As(err, &mbe) {
				http.Error(w, "body too large", http.StatusRequestEntityTooLarge)
				return
			}
			if errors.Is(err, io.EOF) {
				break
			}
			// 与 /upload 同口径：请求体未完整读完时不进入模拟处理阶段，
			// 也不返回可被客户端当作成功工具循环的响应。
			http.Error(w, "body unreadable", http.StatusBadRequest)
			return
		}
	}
	tRecv := nowMicros()

	// 绝对 deadline 等待 proc_ms。
	deadline := time.Now().Add(time.Duration(procMs) * time.Millisecond)
	if d := time.Until(deadline); d > 0 {
		time.Sleep(d)
	}

	body := make([]byte, downBytes)
	rng := rand.New(rand.NewSource(tRecv))
	_, _ = rng.Read(body)

	tSend := nowMicros()
	h := w.Header()
	h.Set("Content-Type", "application/octet-stream")
	h.Set("Content-Length", strconv.FormatInt(downBytes, 10))
	h.Set("X-Aneb-Trecv-Us", strconv.FormatInt(tRecv, 10))
	h.Set("X-Aneb-Tsend-Us", strconv.FormatInt(tSend, 10))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(body)
}
