package main

import (
	"encoding/base64"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// stream 无 profile 时的默认参数。
const (
	defaultStreamTokens = 100
	defaultStreamTps    = 40.0
	defaultStreamMedian = 120.0
	defaultStreamSigma  = 0.6
	defaultStreamSeed   = 1
)

// handleStream GET /api/v1/stream?profile=&phase=&run=&tokens=&rate_tps=&seed=
// （R-04/R-08/R-17/R-20/R-24）
//
// 流程：
//  1. 写响应头（text/event-stream, no-cache）。
//  2. 立即 flush 一个 SSE 注释帧 ": prelude {...}"（R-20：给 T1 一个
//     服务端锚点，把服务端 dwell 从"网络分量"剥离）。
//  3. 按绝对时刻表 pacing（time.Until(start.Add(sched[i]))，禁累加 sleep，
//     防漂移累积），逐 event 发送并 Flush。
//  4. 每 event data 含 {seq, sched_us, pre_flush_us, payload}；payload 一律
//     base64（R-08：杜绝随机字节与 SSE 分隔符 \n\n 冲突）。
//  5. 流末尾 summary event：flush_return_us / timer_late_us / flush_block_us /
//     carryover_us 四个逐 seq 数组。timer 迟到（调度问题→服务端失真候选）与
//     flush 阻塞（TCP 回压→网络回压证据，绝不判失真）分开记录（R-06/R-17）；
//     carryover_us 是进入本迭代时目标时刻已被前序事件的 flush 阻塞/迟到挤过
//     的遗留滞后——它属于前序回压/迟到的残留，不是本事件的调度失真，单独
//     成列以免污染 timer_late_us 的"服务端失真候选"判定（跨事件解耦）。
//
// 全部时间戳为进程锚点微秒差（R-24），墙钟仅 prelude 附带一次锚点映射。
func (a *app) handleStream(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	params, err := a.streamParamsFromRequest(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	inj, err := parseInject(r.URL.Query().Get("inject"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if inj != nil && !a.allowInject {
		// 未开 -allow-inject 一律 403：注入流不是测量数据，生产/取证部署
		// 绝不允许被 URL 参数悄悄触发。
		http.Error(w, "fault injection disabled (server not started with -allow-inject)", http.StatusForbidden)
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}

	specs := GenerateTokens(params)
	n := len(specs)

	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("Cache-Control", "no-cache")
	h.Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)

	// R-20: prelude 注释帧——响应头后、首 token 前的服务端时戳锚点。
	prelude := make([]byte, 0, 128)
	prelude = append(prelude, `: prelude {"srv_ts_us":`...)
	prelude = strconv.AppendInt(prelude, nowMicros(), 10)
	prelude = append(prelude, `,"anchor_wall_unix_ns":`...)
	prelude = strconv.AppendInt(prelude, anchorWallUnixNs, 10)
	prelude = append(prelude, `,"observed":`...)
	// strconv.AppendQuote 做 JSON 兼容转义（RemoteAddr 正常不含特殊字符，
	// 但手拼 JSON 不做转义是脆弱模式，防御性统一）。
	prelude = strconv.AppendQuote(prelude, r.RemoteAddr)
	prelude = append(prelude, "}\n\n"...)
	if _, err := w.Write(prelude); err != nil {
		return
	}
	flusher.Flush()

	// 绝对时刻表锚点：prelude flush 之后起算。
	start := time.Now()
	startUs := nowMicros()

	flushReturnUs := make([]int64, n)
	timerLateUs := make([]int64, n)
	flushBlockUs := make([]int64, n)
	carryoverUs := make([]int64, n)

	// 事件缓冲预分配复用；payload 内容确定性生成（大小由 tokengen 决定）。
	buf := make([]byte, 0, 4096)
	payloadRaw := make([]byte, tokenBytesMax)
	payloadB64 := make([]byte, base64.StdEncoding.EncodedLen(tokenBytesMax))

	ctx := r.Context()
	for i := 0; i < n; i++ {
		// 客户端断开立即退出：Flush 不报错，必须显式查 ctx。
		select {
		case <-ctx.Done():
			return
		default:
		}

		// 绝对时刻 pacing：目标 = start + sched[i]，禁累加 sleep。
		schedAbsUs := startUs + specs[i].SchedUs
		entryUs := nowMicros() // 本迭代进入时刻
		if entryUs <= schedAbsUs {
			// 目标时刻未过：正常 sleep 到绝对时刻。timer_late = 实际唤醒 -
			// 计划时刻，是纯调度误差（R-06：服务端失真候选）；carryover = 0。
			target := start.Add(time.Duration(specs[i].SchedUs) * time.Microsecond)
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
		} else {
			// 目标时刻已过（前一事件的 flush 阻塞或迟到把时间挤过去了）：
			// 不 sleep，立即发送。carryover 记录这份前序回压/迟到的遗留滞后；
			// timer_late 保持 0——本事件根本没进入调度等待，把这份滞后记进
			// timer_late 会让最恶劣的网络回压样本在下一事件上伪装成
			// "服务端调度失真候选"（R-06 跨事件耦合，评审发现）。
			carryoverUs[i] = entryUs - schedAbsUs
		}

		size := specs[i].Size
		fillPayload(payloadRaw[:size], params.Seed, i)
		b64n := base64.StdEncoding.EncodedLen(size)
		base64.StdEncoding.Encode(payloadB64[:b64n], payloadRaw[:size])

		// 故障注入（仅 -allow-inject 时 inj 非 nil；inj.n 为 1-based 事件号）：
		//   dupseq:N    第 N 个 event 的 seq 重复上一个（客户端 seq join 必须
		//               检出重号，禁数组位置配对，R-08）；
		//   malformed:N 第 N 个 event 的 data JSON 被截断半个 payload——SSE
		//               帧仍以 \n\n 完整收尾，坏的只是 JSON（客户端必须跳过
		//               并计 gap，绝不静默错位）；
		//   truncate:N  发出 N 个 event 后直接关流（无 summary，客户端收到
		//               EOF，必须判会话中断而非把最后间隔计入 ITL，R-08/§4.9）。
		seqVal := int64(i)
		if inj != nil && inj.kind == injectDupseq && i+1 == inj.n {
			seqVal = int64(i - 1) // parseInject 保证 dupseq 的 n >= 2
		}

		buf = buf[:0]
		buf = append(buf, "event: token\ndata: {\"seq\":"...)
		buf = strconv.AppendInt(buf, seqVal, 10)
		buf = append(buf, `,"sched_us":`...)
		buf = strconv.AppendInt(buf, schedAbsUs, 10)
		buf = append(buf, `,"pre_flush_us":`...)
		buf = strconv.AppendInt(buf, nowMicros(), 10) // 写入前时刻
		buf = append(buf, `,"payload":"`...)
		if inj != nil && inj.kind == injectMalformed && i+1 == inj.n {
			// 只给半个 payload，且不写收尾的 `"}`——data 行 JSON 必然非法。
			buf = append(buf, payloadB64[:b64n/2]...)
			buf = append(buf, "\n\n"...)
		} else {
			buf = append(buf, payloadB64[:b64n]...)
			buf = append(buf, "\"}\n\n"...)
		}

		if _, err := w.Write(buf); err != nil {
			return // 客户端断开
		}
		flushStart := time.Now()
		flusher.Flush()
		flushBlockUs[i] = time.Since(flushStart).Microseconds() // R-06：回压证据，与调度误差分开
		flushReturnUs[i] = nowMicros()                          // R-17：语义固定为 Flush() 返回之后

		if inj != nil && inj.kind == injectTruncate && i+1 == inj.n {
			return // truncate:N——第 N 个 event 之后直接关流，summary 不发
		}
	}

	// summary event：四个逐 seq 数组分开返回（carryover_us 语义见顶部注释：
	// 前序回压/迟到的遗留，不是本事件的调度失真）。
	buf = buf[:0]
	buf = append(buf, "event: summary\ndata: {\"tokens\":"...)
	buf = strconv.AppendInt(buf, int64(n), 10)
	buf = append(buf, `,"stream_start_us":`...)
	buf = strconv.AppendInt(buf, startUs, 10)
	buf = appendInt64Array(buf, `,"flush_return_us":`, flushReturnUs)
	buf = appendInt64Array(buf, `,"timer_late_us":`, timerLateUs)
	buf = appendInt64Array(buf, `,"flush_block_us":`, flushBlockUs)
	buf = appendInt64Array(buf, `,"carryover_us":`, carryoverUs)
	// P3-C05：流末尾 TCP_INFO 采样（设计 §6 遗留条款）——连接累计重传段数
	// tcpi_total_retrans 随 summary 透出（additive），供客户端把"丢包重传批化"
	// 与"中间盒缓冲批化"区分（netem 取证断言 3 误报的共变量修复）。
	// 非 Linux / h3 / 取不到底层 TCP 连接时字段整体缺省（n/a），客户端按
	// 无共变量数据回退——绝不写 0 顶替（R-10 同款纪律）。
	if retrans, ok := connTotalRetrans(r); ok {
		buf = append(buf, `,"retrans_total":`...)
		buf = strconv.AppendUint(buf, uint64(retrans), 10)
	}
	if inj != nil {
		// 如实记录注入（truncate 到不了这里）：任何带 inject_applied 的
		// summary 都不是测量数据，客户端/离线分析据此剔除。
		buf = append(buf, `,"inject_applied":`...)
		buf = strconv.AppendQuote(buf, inj.String())
	}
	buf = append(buf, "}\n\n"...)
	if _, err := w.Write(buf); err != nil {
		return
	}
	flusher.Flush()
}

// streamParamsFromRequest 解析 stream 参数：有 profile= 时取该 profile 的
// token_stream phase（phase= 为 token_stream 序号，默认 0）；否则用
// tokens=/rate_tps=/seed= 直接指定，缺省 100 token @ 40tps。
func (a *app) streamParamsFromRequest(r *http.Request) (StreamParams, error) {
	q := r.URL.Query()
	params := StreamParams{
		Seed:    defaultStreamSeed,
		Tokens:  defaultStreamTokens,
		RateTps: defaultStreamTps,
		Median:  defaultStreamMedian,
		Sigma:   defaultStreamSigma,
	}
	if pid := q.Get("profile"); pid != "" {
		p, ok := a.profiles[pid]
		if !ok {
			return params, errBadParam("unknown profile: " + pid)
		}
		phaseIdx := 0
		if s := q.Get("phase"); s != "" {
			v, err := strconv.Atoi(s)
			if err != nil || v < 0 {
				return params, errBadParam("invalid phase: " + s)
			}
			phaseIdx = v
		}
		ph, err := p.tokenStreamPhase(phaseIdx)
		if err != nil {
			return params, errBadParam(err.Error())
		}
		params.Tokens = ph.Tokens
		params.RateTps = ph.RateTps
		params.Seed = ph.Seed
		params.Burst = ph.Burst
		if ph.TokenBytes != nil {
			params.Median = ph.TokenBytes.Median
			params.Sigma = ph.TokenBytes.Sigma
		}
	}
	// 显式参数覆盖（无 profile 时即直接指定路径）。
	if s := q.Get("tokens"); s != "" {
		v, err := strconv.Atoi(s)
		if err != nil || v <= 0 || v > 100000 {
			return params, errBadParam("invalid tokens: " + s)
		}
		params.Tokens = v
	}
	if s := q.Get("rate_tps"); s != "" {
		// burst phase 的节奏由 burst.cluster_tps 等字段驱动，GenerateTokens
		// 完全忽略 RateTps——静默接受会让调用方误以为已改变节奏，显式拒绝。
		if params.Burst != nil {
			return params, errBadParam("rate_tps override not supported for burst phases")
		}
		// 下限 0.1 tps：防止极小速率把调度表拉长到不合理时长（单请求
		// goroutine 挂数天的慢速拒绝服务面）。
		v, err := strconv.ParseFloat(s, 64)
		if err != nil || v < 0.1 || v > 100000 {
			return params, errBadParam("invalid rate_tps: " + s)
		}
		params.RateTps = v
	}
	if s := q.Get("seed"); s != "" {
		v, err := strconv.ParseInt(s, 10, 64)
		if err != nil {
			return params, errBadParam("invalid seed: " + s)
		}
		params.Seed = v
	}
	return params, nil
}

type errBadParam string

func (e errBadParam) Error() string { return string(e) }

// 故障注入类型（P0-C13 前置；语义见 handleStream 循环内注释）。
const (
	injectTruncate  = "truncate"
	injectMalformed = "malformed"
	injectDupseq    = "dupseq"
)

// injectSpec 描述一次 /stream 故障注入：kind + 1-based 事件号 n。
type injectSpec struct {
	kind string
	n    int
}

func (s *injectSpec) String() string {
	return s.kind + ":" + strconv.Itoa(s.n)
}

// parseInject 解析 &inject=kind:N。空串返回 (nil, nil)。注意本函数只管
// 语法合法性，权限（-allow-inject）由调用方把关。
func parseInject(raw string) (*injectSpec, error) {
	if raw == "" {
		return nil, nil
	}
	kind, ns, ok := strings.Cut(raw, ":")
	if !ok {
		return nil, errBadParam("invalid inject (want kind:N): " + raw)
	}
	switch kind {
	case injectTruncate, injectMalformed, injectDupseq:
	default:
		return nil, errBadParam("invalid inject kind: " + kind)
	}
	n, err := strconv.Atoi(ns)
	if err != nil || n < 1 {
		return nil, errBadParam("invalid inject event number: " + ns)
	}
	if kind == injectDupseq && n < 2 {
		return nil, errBadParam("inject dupseq needs N >= 2 (event 1 has no predecessor)")
	}
	return &injectSpec{kind: kind, n: n}, nil
}

// fillPayload 用确定性伪随机字节填充 payload（内容仅需非空且确定性，
// 大小序列的确定性由 tokengen 保证；base64 编码后不可能与 \n\n 冲突）。
func fillPayload(dst []byte, seed int64, seq int) {
	x := uint64(seed)*0x9E3779B97F4A7C15 + uint64(seq)*0xBF58476D1CE4E5B9 + 1
	for i := range dst {
		// xorshift64*
		x ^= x >> 12
		x ^= x << 25
		x ^= x >> 27
		dst[i] = byte((x * 0x2545F4914F6CDD1D) >> 56)
	}
}

func appendInt64Array(buf []byte, prefix string, vals []int64) []byte {
	buf = append(buf, prefix...)
	buf = append(buf, '[')
	for i, v := range vals {
		if i > 0 {
			buf = append(buf, ',')
		}
		buf = strconv.AppendInt(buf, v, 10)
	}
	buf = append(buf, ']')
	return buf
}
