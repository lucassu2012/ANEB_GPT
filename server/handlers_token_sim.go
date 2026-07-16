package main

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"time"
)

const (
	tokenSimTaskContract    = "aneb-token-task-v1"
	tokenSimMaxPlanBytes    = 1 << 20
	// Token Stress Profile 需要 100MiB 视频突发；保留 128MiB 硬上限，
	// 既容纳协议/版本演进余量，也避免任意超大请求耗尽节点资源。
	tokenSimMaxUploadBytes  = 128 << 20
	tokenSimMaxRequestBytes = tokenSimMaxPlanBytes + tokenSimMaxUploadBytes + 4
	tokenSimMaxTokens       = 10_000
	tokenSimMaxTokenBytes   = 4_096
)

// tokenSimTaskPlan is the exact, per-task business schedule selected by the
// Android runtime from a hash-bound runtime_plan.json. It intentionally has no
// field for RTT, loss, jitter, or synthetic network impairment.
type tokenSimTaskPlan struct {
	ContractVersion    string    `json:"contract_version"`
	TaskID             string    `json:"task_id"`
	WorkloadKind       string    `json:"workload_kind"`
	Seed               int64     `json:"seed"`
	ProcessingMs       float64   `json:"processing_ms"`
	UploadPayloadBytes int64     `json:"upload_payload_bytes"`
	TokenIntervalsMs   []float64 `json:"token_intervals_ms"`
	TokenSizesBytes    []int     `json:"token_sizes_bytes"`
}

type tokenSimPrelude struct {
	ContractVersion      string `json:"contract_version"`
	TaskID               string `json:"task_id"`
	WorkloadKind         string `json:"workload_kind"`
	UploadBytes          int64  `json:"upload_bytes"`
	UploadRecvStartUs    int64  `json:"upload_recv_start_us"`
	UploadRecvEndUs      int64  `json:"upload_recv_end_us"`
	ProcessingStartUs    int64  `json:"processing_start_us"`
	ProcessingDeadlineUs int64  `json:"processing_deadline_us"`
	Observed             string `json:"observed"`
}

type tokenSimToken struct {
	Seq        int    `json:"seq"`
	SchedUs    int64  `json:"sched_us"`
	PreFlushUs int64  `json:"pre_flush_us"`
	SizeBytes  int    `json:"size_bytes"`
	Payload    string `json:"payload"`
}

type tokenSimSummary struct {
	TaskID            string  `json:"task_id"`
	Tokens            int     `json:"tokens"`
	ProcessingReadyUs int64   `json:"processing_ready_us"`
	FlushReturnUs     []int64 `json:"flush_return_us"`
	TimerLateUs       []int64 `json:"timer_late_us"`
	FlushBlockUs      []int64 `json:"flush_block_us"`
	CarryoverUs       []int64 `json:"carryover_us"`
}

// handleTokenSim executes one multimodal AI task over one request:
//  1. read a 4-byte big-endian JSON plan length and the plan;
//  2. read exactly upload_payload_bytes while timestamping node receipt;
//  3. flush an SSE prelude, wait the planned processing baseline;
//  4. emit every token at the exact absolute schedule and flush individually.
//
// The endpoint never invents a network outcome. Delay/loss/batching visible at
// the client is produced by the real path between the App and this node.
func (a *app) handleTokenSim(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	body := http.MaxBytesReader(w, r.Body, tokenSimMaxRequestBytes)
	plan, encodedPlanBytes, err := readTokenSimPlan(body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if r.ContentLength >= 0 {
		expected := int64(4+encodedPlanBytes) + plan.UploadPayloadBytes
		if r.ContentLength != expected {
			http.Error(w, "content length does not match plan", http.StatusBadRequest)
			return
		}
	}

	recvStartUs := nowMicros()
	buf := make([]byte, uploadChunkSize)
	remaining := plan.UploadPayloadBytes
	for remaining > 0 {
		n, readErr := body.Read(buf[:minInt64(int64(len(buf)), remaining)])
		if n > 0 {
			remaining -= int64(n)
		}
		if readErr != nil {
			if remaining == 0 && errors.Is(readErr, io.EOF) {
				break
			}
			if errors.Is(readErr, io.EOF) {
				http.Error(w, "upload payload truncated", http.StatusBadRequest)
			} else {
				http.Error(w, "read upload payload: "+readErr.Error(), http.StatusBadRequest)
			}
			return
		}
	}
	var extra [1]byte
	if n, readErr := body.Read(extra[:]); n != 0 || (readErr != nil && !errors.Is(readErr, io.EOF)) {
		http.Error(w, "upload payload exceeds plan", http.StatusBadRequest)
		return
	}
	recvEndUs := nowMicros()

	processingStart := time.Now()
	processingStartUs := nowMicros()
	processingDuration := time.Duration(math.Round(plan.ProcessingMs*1000)) * time.Microsecond
	processingDeadline := processingStart.Add(processingDuration)
	processingDeadlineUs := processingStartUs + processingDuration.Microseconds()

	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("Cache-Control", "no-cache")
	h.Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	prelude := tokenSimPrelude{
		ContractVersion:      tokenSimTaskContract,
		TaskID:               plan.TaskID,
		WorkloadKind:         plan.WorkloadKind,
		UploadBytes:          plan.UploadPayloadBytes,
		UploadRecvStartUs:    recvStartUs,
		UploadRecvEndUs:      recvEndUs,
		ProcessingStartUs:    processingStartUs,
		ProcessingDeadlineUs: processingDeadlineUs,
		Observed:             r.RemoteAddr,
	}
	if err := writeSSEJSON(w, "prelude", prelude); err != nil {
		return
	}
	flusher.Flush()

	if !waitUntil(r, processingDeadline) {
		return
	}
	processingReadyUs := nowMicros()
	n := len(plan.TokenIntervalsMs)
	flushReturnUs := make([]int64, n)
	timerLateUs := make([]int64, n)
	flushBlockUs := make([]int64, n)
	carryoverUs := make([]int64, n)
	cumulative := time.Duration(0)
	for i, intervalMs := range plan.TokenIntervalsMs {
		cumulative += time.Duration(math.Round(intervalMs*1000)) * time.Microsecond
		target := processingDeadline.Add(cumulative)
		targetUs := processingDeadlineUs + cumulative.Microseconds()
		entryUs := nowMicros()
		if entryUs <= targetUs {
			if !waitUntil(r, target) {
				return
			}
			if late := nowMicros() - targetUs; late > 0 {
				timerLateUs[i] = late
			}
		} else {
			carryoverUs[i] = entryUs - targetUs
		}

		raw := make([]byte, plan.TokenSizesBytes[i])
		fillPayload(raw, plan.Seed, i)
		event := tokenSimToken{
			Seq:        i,
			SchedUs:    targetUs,
			PreFlushUs: nowMicros(),
			SizeBytes:  len(raw),
			Payload:    base64.StdEncoding.EncodeToString(raw),
		}
		if err := writeSSEJSON(w, "token", event); err != nil {
			return
		}
		flushStart := time.Now()
		flusher.Flush()
		flushBlockUs[i] = time.Since(flushStart).Microseconds()
		flushReturnUs[i] = nowMicros()
	}

	summary := tokenSimSummary{
		TaskID:            plan.TaskID,
		Tokens:            n,
		ProcessingReadyUs: processingReadyUs,
		FlushReturnUs:     flushReturnUs,
		TimerLateUs:       timerLateUs,
		FlushBlockUs:      flushBlockUs,
		CarryoverUs:       carryoverUs,
	}
	if err := writeSSEJSON(w, "summary", summary); err == nil {
		flusher.Flush()
	}
}

func readTokenSimPlan(r io.Reader) (tokenSimTaskPlan, int, error) {
	var plan tokenSimTaskPlan
	var lengthPrefix [4]byte
	if _, err := io.ReadFull(r, lengthPrefix[:]); err != nil {
		return plan, 0, fmt.Errorf("read plan length: %w", err)
	}
	planBytes := int(binary.BigEndian.Uint32(lengthPrefix[:]))
	if planBytes <= 0 || planBytes > tokenSimMaxPlanBytes {
		return plan, 0, fmt.Errorf("invalid plan length")
	}
	encoded := make([]byte, planBytes)
	if _, err := io.ReadFull(r, encoded); err != nil {
		return plan, 0, fmt.Errorf("read plan: %w", err)
	}
	decoder := json.NewDecoder(bytes.NewReader(encoded))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&plan); err != nil {
		return plan, 0, fmt.Errorf("decode plan: %w", err)
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return plan, 0, fmt.Errorf("plan contains trailing JSON data")
	}
	if err := validateTokenSimPlan(plan); err != nil {
		return plan, 0, err
	}
	return plan, planBytes, nil
}

func validateTokenSimPlan(plan tokenSimTaskPlan) error {
	if plan.ContractVersion != tokenSimTaskContract {
		return fmt.Errorf("unsupported contract_version")
	}
	if plan.TaskID == "" || len(plan.TaskID) > 128 || plan.WorkloadKind == "" || len(plan.WorkloadKind) > 64 {
		return fmt.Errorf("invalid task identity")
	}
	switch plan.WorkloadKind {
	case "text", "document", "image", "video":
	default:
		return fmt.Errorf("unsupported workload_kind")
	}
	if math.IsNaN(plan.ProcessingMs) || math.IsInf(plan.ProcessingMs, 0) || plan.ProcessingMs < 0 || plan.ProcessingMs > 60_000 {
		return fmt.Errorf("invalid processing_ms")
	}
	if plan.UploadPayloadBytes <= 0 || plan.UploadPayloadBytes > tokenSimMaxUploadBytes {
		return fmt.Errorf("invalid upload_payload_bytes")
	}
	if len(plan.TokenIntervalsMs) == 0 || len(plan.TokenIntervalsMs) > tokenSimMaxTokens || len(plan.TokenIntervalsMs) != len(plan.TokenSizesBytes) {
		return fmt.Errorf("invalid token schedule lengths")
	}
	if plan.TokenIntervalsMs[0] != 0 {
		return fmt.Errorf("first token interval must be zero")
	}
	for i, interval := range plan.TokenIntervalsMs {
		if math.IsNaN(interval) || math.IsInf(interval, 0) || interval < 0 || interval > 60_000 {
			return fmt.Errorf("invalid token interval at %d", i)
		}
		if plan.TokenSizesBytes[i] <= 0 || plan.TokenSizesBytes[i] > tokenSimMaxTokenBytes {
			return fmt.Errorf("invalid token size at %d", i)
		}
	}
	return nil
}

func writeSSEJSON(w io.Writer, event string, value any) error {
	encoded, err := json.Marshal(value)
	if err != nil {
		return err
	}
	_, err = fmt.Fprintf(w, "event: %s\ndata: %s\n\n", event, encoded)
	return err
}

func waitUntil(r *http.Request, deadline time.Time) bool {
	d := time.Until(deadline)
	if d <= 0 {
		return true
	}
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-r.Context().Done():
		return false
	case <-timer.C:
		return true
	}
}

func minInt64(a, b int64) int {
	if a < b {
		return int(a)
	}
	return int(b)
}
