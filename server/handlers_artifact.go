package main

import (
	"encoding/base64"
	"net/http"
	"strconv"
	"time"
)

// artifact_stream 端点边界（§3.1，有界防单请求长挂 / 响应体过大）。
const (
	artifactDefaultBytes      int64 = 4 << 20  // 4MiB
	artifactMaxBytes          int64 = 32 << 20 // 32MiB
	artifactDefaultChunkKB          = 64
	artifactMinChunkKB              = 16
	artifactMaxChunkKB              = 1024
	artifactDefaultCadenceBps int64 = 2_000_000      // 2 MB/s 默认生成节奏
	artifactMinCadenceBps     int64 = 100_000        // 0.1 MB/s 下限（防极慢流长占 goroutine）
	artifactMaxCadenceBps     int64 = 10_000_000_000 // ~10 GB/s（≈不限速上限）
	artifactMaxDurationS            = 60.0           // bytes/cadence 时长上限
	artifactMaxClassLen             = 32
)

// artifactParams 是 artifact_stream 的解析后参数。
type artifactParams struct {
	Bytes      int64
	ChunkBytes int
	CadenceBps int64
	Class      string // doc/image/video（诊断标签，随 prelude 透出）
	Seed       int64
}

// handleArtifactStream GET /api/v1/artifact_stream?bytes=&chunk_kb=&cadence_bps=&class=&seed=
// （或 profile=&phase= 取 artifact_stream 相位）。
//
// 下行「渐进生成」内容模型（PROFILE_FRAMEWORK §3.1）：按 generation cadence（bytes/s）**限速**
// 吐出 total_bytes——区别于 /download 不限速裸测，建模「AI 边生成边下发」doc/image/video。
// 每 chunk 事件带服务端 sched_us/pre_flush_us 时戳（进程锚点单调 us，口径同 /stream），
// 供客户端评估「网络交付是否跟得上生成节奏」（token 流 ITL 的下行大对象类比）。
//
// 注：本端点提供**刺激 + 服务端时戳**；客户端「交付-vs-节奏」KPI 的落地为后续（见 PR 说明）。
func (a *app) handleArtifactStream(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	p, err := a.artifactParamsFromRequest(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}

	numChunks := int((p.Bytes + int64(p.ChunkBytes) - 1) / int64(p.ChunkBytes))

	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("Cache-Control", "no-cache")
	h.Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)

	prelude := make([]byte, 0, 192)
	prelude = append(prelude, `: prelude {"srv_ts_us":`...)
	prelude = strconv.AppendInt(prelude, nowMicros(), 10)
	prelude = append(prelude, `,"anchor_wall_unix_ns":`...)
	prelude = strconv.AppendInt(prelude, anchorWallUnixNs, 10)
	prelude = append(prelude, `,"artifact_class":`...)
	prelude = strconv.AppendQuote(prelude, p.Class)
	prelude = append(prelude, `,"total_bytes":`...)
	prelude = strconv.AppendInt(prelude, p.Bytes, 10)
	prelude = append(prelude, `,"cadence_bps":`...)
	prelude = strconv.AppendInt(prelude, p.CadenceBps, 10)
	prelude = append(prelude, "}\n\n"...)
	if _, err := w.Write(prelude); err != nil {
		return
	}
	flusher.Flush()

	start := time.Now()
	startUs := nowMicros()
	flushReturnUs := make([]int64, numChunks)
	timerLateUs := make([]int64, numChunks)

	chunkRaw := make([]byte, p.ChunkBytes)
	chunkB64 := make([]byte, base64.StdEncoding.EncodedLen(p.ChunkBytes))
	buf := make([]byte, 0, base64.StdEncoding.EncodedLen(p.ChunkBytes)+160)

	ctx := r.Context()
	var sentBytes int64
	for i := 0; i < numChunks; i++ {
		select {
		case <-ctx.Done():
			return
		default:
		}
		size := p.ChunkBytes
		if remaining := p.Bytes - sentBytes; remaining < int64(size) {
			size = int(remaining)
		}
		// 生成节奏 pacing（绝对时刻，禁累加 sleep）：本 chunk 计划发出 = 已发字节 / cadence。
		schedUs := sentBytes * 1_000_000 / p.CadenceBps
		schedAbsUs := startUs + schedUs
		target := start.Add(time.Duration(schedUs) * time.Microsecond)
		if d := time.Until(target); d > 0 {
			timer := time.NewTimer(d)
			select {
			case <-ctx.Done():
				timer.Stop()
				return
			case <-timer.C:
			}
		}
		if late := nowMicros() - schedAbsUs; late > 0 {
			timerLateUs[i] = late
		}

		fillPayload(chunkRaw[:size], p.Seed, i)
		b64n := base64.StdEncoding.EncodedLen(size)
		base64.StdEncoding.Encode(chunkB64[:b64n], chunkRaw[:size])

		buf = buf[:0]
		buf = append(buf, "event: chunk\ndata: {\"seq\":"...)
		buf = strconv.AppendInt(buf, int64(i), 10)
		buf = append(buf, `,"sched_us":`...)
		buf = strconv.AppendInt(buf, schedAbsUs, 10)
		buf = append(buf, `,"pre_flush_us":`...)
		buf = strconv.AppendInt(buf, nowMicros(), 10)
		buf = append(buf, `,"bytes":`...)
		buf = strconv.AppendInt(buf, int64(size), 10)
		buf = append(buf, `,"payload":"`...)
		buf = append(buf, chunkB64[:b64n]...)
		buf = append(buf, "\"}\n\n"...)
		if _, err := w.Write(buf); err != nil {
			return
		}
		flusher.Flush()
		flushReturnUs[i] = nowMicros()
		sentBytes += int64(size)
	}

	buf = buf[:0]
	buf = append(buf, "event: summary\ndata: {\"total_bytes\":"...)
	buf = strconv.AppendInt(buf, sentBytes, 10)
	buf = append(buf, `,"chunks":`...)
	buf = strconv.AppendInt(buf, int64(numChunks), 10)
	buf = append(buf, `,"cadence_bps":`...)
	buf = strconv.AppendInt(buf, p.CadenceBps, 10)
	buf = append(buf, `,"stream_start_us":`...)
	buf = strconv.AppendInt(buf, startUs, 10)
	buf = appendInt64Array(buf, `,"flush_return_us":`, flushReturnUs)
	buf = appendInt64Array(buf, `,"timer_late_us":`, timerLateUs)
	buf = append(buf, "}\n\n"...)
	if _, err := w.Write(buf); err != nil {
		return
	}
	flusher.Flush()
}

func (a *app) artifactParamsFromRequest(r *http.Request) (artifactParams, error) {
	q := r.URL.Query()
	p := artifactParams{
		Bytes:      artifactDefaultBytes,
		ChunkBytes: artifactDefaultChunkKB << 10,
		CadenceBps: artifactDefaultCadenceBps,
		Class:      "doc",
		Seed:       1,
	}
	if pid := q.Get("profile"); pid != "" {
		prof, ok := a.profiles[pid]
		if !ok {
			return p, errBadParam("unknown profile: " + pid)
		}
		phaseIdx := 0
		if s := q.Get("phase"); s != "" {
			v, err := strconv.Atoi(s)
			if err != nil || v < 0 {
				return p, errBadParam("invalid phase: " + s)
			}
			phaseIdx = v
		}
		ph, err := prof.artifactStreamPhase(phaseIdx)
		if err != nil {
			return p, errBadParam(err.Error())
		}
		if ph.Bytes > 0 {
			p.Bytes = ph.Bytes
		}
		if ph.ChunkKB > 0 {
			p.ChunkBytes = ph.ChunkKB << 10
		}
		if ph.CadenceBps > 0 {
			p.CadenceBps = ph.CadenceBps
		}
		if ph.ArtifactClass != "" {
			p.Class = ph.ArtifactClass
		}
		if ph.Seed != 0 {
			p.Seed = ph.Seed
		}
	}
	if s := q.Get("bytes"); s != "" {
		v, err := strconv.ParseInt(s, 10, 64)
		if err != nil {
			return p, errBadParam("invalid bytes: " + s)
		}
		p.Bytes = v
	}
	if s := q.Get("chunk_kb"); s != "" {
		v, err := strconv.Atoi(s)
		if err != nil {
			return p, errBadParam("invalid chunk_kb: " + s)
		}
		p.ChunkBytes = v << 10
	}
	if s := q.Get("cadence_bps"); s != "" {
		v, err := strconv.ParseInt(s, 10, 64)
		if err != nil {
			return p, errBadParam("invalid cadence_bps: " + s)
		}
		p.CadenceBps = v
	}
	if s := q.Get("class"); s != "" {
		p.Class = s
	}
	if s := q.Get("seed"); s != "" {
		v, err := strconv.ParseInt(s, 10, 64)
		if err != nil {
			return p, errBadParam("invalid seed: " + s)
		}
		p.Seed = v
	}
	// ---- 校验（越界即 400，避免运行期才失败）----
	if p.Bytes < 1 || p.Bytes > artifactMaxBytes {
		return p, errBadParam("bytes must be in [1," + strconv.FormatInt(artifactMaxBytes, 10) + "]")
	}
	chunkKB := p.ChunkBytes >> 10
	if chunkKB < artifactMinChunkKB || chunkKB > artifactMaxChunkKB {
		return p, errBadParam("chunk_kb must be in [" + strconv.Itoa(artifactMinChunkKB) + "," + strconv.Itoa(artifactMaxChunkKB) + "]")
	}
	if p.CadenceBps < artifactMinCadenceBps || p.CadenceBps > artifactMaxCadenceBps {
		return p, errBadParam("cadence_bps must be in [" + strconv.FormatInt(artifactMinCadenceBps, 10) + "," + strconv.FormatInt(artifactMaxCadenceBps, 10) + "]")
	}
	if float64(p.Bytes)/float64(p.CadenceBps) > artifactMaxDurationS {
		return p, errBadParam("bytes/cadence exceeds max stream duration")
	}
	if len(p.Class) > artifactMaxClassLen {
		return p, errBadParam("class too long")
	}
	return p, nil
}
