package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type prototypeSleepFunc func(context.Context, time.Duration) error
type prototypeNowFunc func() time.Time

const (
	// The capability document advertises the canonical evidence receipt. The
	// stream protocol version governs the exact server-known terminal subset;
	// Android enriches it only after complete SSE decoding.
	prototypeCanonicalTerminalReceiptVersion = "prototype-terminal-receipt-0.1"
)

type prototypeRunRequest struct {
	ProtocolVersion       string `json:"protocol_version"`
	CampaignID            string `json:"campaign_id"`
	RunID                 string `json:"run_id"`
	CampaignMode          string `json:"campaign_mode"`
	RunIndex              int    `json:"run_index"`
	WorkloadID            string `json:"workload_id"`
	WorkloadVersion       string `json:"workload_version"`
	ProfileID             string `json:"profile_id"`
	ProfileVersion        string `json:"profile_version"`
	ProfileManifestSHA256 string `json:"profile_manifest_sha256"`
	ConditionID           string `json:"condition_id"`
	ConditionVersion      string `json:"condition_version"`
}

// prototypeRunIdentity describes an active stream reservation. A concurrent
// request with the same run ID is rejected until the handler returns; later
// requests may start a fresh stream for the planned run.
type prototypeRunIdentity struct {
	CampaignID            string
	CampaignMode          string
	RunIndex              int
	WorkloadID            string
	WorkloadVersion       string
	ProfileID             string
	ProfileVersion        string
	ProfileManifestSHA256 string
	ConditionID           string
	ConditionVersion      string
	ScheduleHash          string
}

type prototypeSSEEnvelope struct {
	SchemaVersion     string `json:"schema_version"`
	ProtocolVersion   string `json:"protocol_version"`
	CampaignID        string `json:"campaign_id"`
	RunID             string `json:"run_id"`
	ConditionID       string `json:"condition_id"`
	EventType         string `json:"event_type"`
	ServerMonotonicNs int64  `json:"server_monotonic_ns"`
	ClockSource       string `json:"clock_source"`
	ClockUnit         string `json:"clock_unit"`
	ClockEpoch        string `json:"clock_epoch"`
	Source            string `json:"source"`
	Details           any    `json:"details"`
}

type prototypeRunStartedDetails struct {
	ProfileID             string `json:"profile_id"`
	ProfileVersion        string `json:"profile_version"`
	ProfileManifestSHA256 string `json:"profile_manifest_sha256"`
	ScheduleHash          string `json:"schedule_hash"`
	NominalIntervalMs     int64  `json:"nominal_interval_ms"`
	T0MonotonicNs         int64  `json:"t0_monotonic_ns"`
}

type prototypeContentDetails struct {
	Seq                   int    `json:"seq"`
	PlannedOffsetMs       int64  `json:"planned_offset_ms"`
	PayloadID             string `json:"payload_id"`
	ProfileManifestSHA256 string `json:"profile_manifest_sha256"`
	ScheduleHash          string `json:"schedule_hash"`
}

// prototypeTerminalWireReceipt is the protocol_version-governed server
// terminal subset. It carries only server-known completion facts and echoed
// request identity; Android enriches the canonical evidence after decoding.
type prototypeTerminalWireReceipt struct {
	ProtocolVersion       string `json:"protocol_version"`
	CampaignID            string `json:"campaign_id"`
	RunID                 string `json:"run_id"`
	CampaignMode          string `json:"campaign_mode"`
	RunIndex              int    `json:"run_index"`
	ConditionID           string `json:"condition_id"`
	ConditionVersion      string `json:"condition_version"`
	ProfileID             string `json:"profile_id"`
	ProfileVersion        string `json:"profile_version"`
	ProfileManifestSHA256 string `json:"profile_manifest_sha256"`
	ScheduleHash          string `json:"schedule_hash"`
	NominalIntervalMs     int64  `json:"nominal_interval_ms"`
	PlannedEventCount     int    `json:"planned_event_count"`
	EmittedEventCount     int    `json:"emitted_event_count"`
	TerminalStatus        string `json:"terminal_status"`
}

type prototypeFailureDetails struct {
	FailureReason  string `json:"failure_reason"`
	EventsReceived int    `json:"events_received"`
}

type prototypeIncompatibleError string

func (e prototypeIncompatibleError) Error() string { return string(e) }

func (a *app) handlePrototypeRun(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writePrototypeJSONError(w, http.StatusMethodNotAllowed, "server_rejected", "method not allowed")
		return
	}
	if r.URL.RawQuery != "" || r.URL.ForceQuery {
		writePrototypeJSONError(w, http.StatusBadRequest, "server_rejected", "query parameters are not supported")
		return
	}
	request, err := decodePrototypeRunRequest(r)
	if err != nil {
		writePrototypeJSONError(w, http.StatusBadRequest, "server_rejected", err.Error())
		return
	}
	schedule, err := validatePrototypeRunRequest(request)
	if err != nil {
		if _, incompatible := err.(prototypeIncompatibleError); incompatible {
			writePrototypeJSONError(w, http.StatusConflict, "incompatible", err.Error())
			return
		}
		writePrototypeJSONError(w, http.StatusBadRequest, "server_rejected", err.Error())
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		writePrototypeJSONError(w, http.StatusInternalServerError, "server_rejected", "streaming unsupported")
		return
	}
	if !a.reservePrototypeRun(request, schedule) {
		writePrototypeJSONError(w, http.StatusConflict, "run_conflict", "run_id is already reserved")
		return
	}
	defer a.releasePrototypeRun(request.RunID)

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)

	now := time.Now
	if a.prototypeNow != nil {
		now = a.prototypeNow
	}
	logicalStart := now()
	t0 := time.Since(procStart).Nanoseconds()
	if err := writePrototypeSSE(w, flusher, "run_started", prototypeSSEEnvelope{
		SchemaVersion:     "aneb-prototype-evidence-0.1",
		ProtocolVersion:   "prototype-stream-0.1",
		CampaignID:        request.CampaignID,
		RunID:             request.RunID,
		ConditionID:       request.ConditionID,
		EventType:         "run_started",
		ServerMonotonicNs: t0,
		ClockSource:       "server.monotonic",
		ClockUnit:         "ns",
		ClockEpoch:        "process",
		Source:            "server",
		Details: prototypeRunStartedDetails{
			ProfileID:             request.ProfileID,
			ProfileVersion:        request.ProfileVersion,
			ProfileManifestSHA256: request.ProfileManifestSHA256,
			ScheduleHash:          schedule.ScheduleHash,
			NominalIntervalMs:     schedule.NominalIntervalMs,
			T0MonotonicNs:         t0,
		},
	}); err != nil {
		return
	}

	for _, event := range schedule.Events {
		if err := waitPrototypeOffset(r.Context(), logicalStart, event.PlannedOffsetMs, a.prototypeSleep, now); err != nil {
			_ = writePrototypeFailure(w, flusher, request, event.Seq-1, err)
			return
		}
		if err := writePrototypeSSE(w, flusher, "content_event", prototypeSSEEnvelope{
			SchemaVersion:     "aneb-prototype-evidence-0.1",
			ProtocolVersion:   "prototype-stream-0.1",
			CampaignID:        request.CampaignID,
			RunID:             request.RunID,
			ConditionID:       request.ConditionID,
			EventType:         "content_event",
			ServerMonotonicNs: time.Since(procStart).Nanoseconds(),
			ClockSource:       "server.monotonic",
			ClockUnit:         "ns",
			ClockEpoch:        "process",
			Source:            "server",
			Details: prototypeContentDetails{
				Seq:                   event.Seq,
				PlannedOffsetMs:       event.PlannedOffsetMs,
				PayloadID:             event.PayloadID,
				ProfileManifestSHA256: request.ProfileManifestSHA256,
				ScheduleHash:          schedule.ScheduleHash,
			},
		}); err != nil {
			return
		}
	}
	if err := waitPrototypeOffset(r.Context(), logicalStart, schedule.TerminalOffsetMs, a.prototypeSleep, now); err != nil {
		_ = writePrototypeFailure(w, flusher, request, len(schedule.Events), err)
		return
	}

	receipt := prototypeTerminalWireReceipt{
		ProtocolVersion:       "prototype-stream-0.1",
		CampaignID:            request.CampaignID,
		RunID:                 request.RunID,
		CampaignMode:          request.CampaignMode,
		RunIndex:              request.RunIndex,
		ConditionID:           request.ConditionID,
		ConditionVersion:      request.ConditionVersion,
		ProfileID:             request.ProfileID,
		ProfileVersion:        request.ProfileVersion,
		ProfileManifestSHA256: request.ProfileManifestSHA256,
		ScheduleHash:          schedule.ScheduleHash,
		NominalIntervalMs:     schedule.NominalIntervalMs,
		PlannedEventCount:     len(schedule.Events),
		EmittedEventCount:     len(schedule.Events),
		TerminalStatus:        "complete",
	}
	_ = writePrototypeSSE(w, flusher, "done", prototypeSSEEnvelope{
		SchemaVersion:     "aneb-prototype-evidence-0.1",
		ProtocolVersion:   "prototype-stream-0.1",
		CampaignID:        request.CampaignID,
		RunID:             request.RunID,
		ConditionID:       request.ConditionID,
		EventType:         "terminal_event",
		ServerMonotonicNs: time.Since(procStart).Nanoseconds(),
		ClockSource:       "server.monotonic",
		ClockUnit:         "ns",
		ClockEpoch:        "process",
		Source:            "server",
		Details:           receipt,
	})
}

func decodePrototypeRunRequest(r *http.Request) (prototypeRunRequest, error) {
	if r.Body == nil {
		return prototypeRunRequest{}, errors.New("request body required")
	}
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20+1))
	if err != nil {
		return prototypeRunRequest{}, fmt.Errorf("read request: %w", err)
	}
	if len(raw) > 1<<20 {
		return prototypeRunRequest{}, errors.New("request body too large")
	}
	if err := rejectDuplicatePrototypeRequestFields(raw); err != nil {
		return prototypeRunRequest{}, err
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	var request prototypeRunRequest
	if err := decoder.Decode(&request); err != nil {
		return prototypeRunRequest{}, fmt.Errorf("invalid request: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		if err == nil {
			return prototypeRunRequest{}, errors.New("multiple JSON values")
		}
		return prototypeRunRequest{}, fmt.Errorf("invalid trailing JSON: %w", err)
	}
	return request, nil
}

func rejectDuplicatePrototypeRequestFields(raw []byte) error {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	token, err := decoder.Token()
	if err != nil {
		return fmt.Errorf("invalid request: %w", err)
	}
	delimiter, ok := token.(json.Delim)
	if !ok || delimiter != '{' {
		return errors.New("request must be a JSON object")
	}
	seen := make(map[string]struct{})
	knownFields := map[string]struct{}{
		"protocol_version":        {},
		"campaign_id":             {},
		"run_id":                  {},
		"campaign_mode":           {},
		"run_index":               {},
		"workload_id":             {},
		"workload_version":        {},
		"profile_id":              {},
		"profile_version":         {},
		"profile_manifest_sha256": {},
		"condition_id":            {},
		"condition_version":       {},
	}
	for decoder.More() {
		keyStart := decoder.InputOffset()
		keyToken, err := decoder.Token()
		if err != nil {
			return fmt.Errorf("invalid request field: %w", err)
		}
		keyEnd := decoder.InputOffset()
		key, ok := keyToken.(string)
		if !ok {
			return errors.New("request field name must be a string")
		}
		if keyStart < 0 || keyEnd < keyStart || keyEnd > int64(len(raw)) {
			return errors.New("request field bounds invalid")
		}
		// More leaves the comma separator at the decoder position for all
		// fields after the first. Advance only over that separator and JSON
		// whitespace so the slice covers the lexical key token itself.
		for keyStart < keyEnd {
			switch raw[keyStart] {
			case ',', ' ', '\t', '\r', '\n':
				keyStart++
			default:
				break
			}
			if keyStart < keyEnd && raw[keyStart] != ',' && raw[keyStart] != ' ' && raw[keyStart] != '\t' && raw[keyStart] != '\r' && raw[keyStart] != '\n' {
				break
			}
		}
		rawKey := bytes.TrimSpace(raw[keyStart:keyEnd])
		if _, known := knownFields[key]; known {
			expectedRaw, _ := json.Marshal(key)
			if !bytes.Equal(rawKey, expectedRaw) {
				return fmt.Errorf("request field spelling must be exact: %s", key)
			}
		} else {
			for knownField := range knownFields {
				if strings.EqualFold(knownField, key) {
					return fmt.Errorf("request field spelling must be exact: %s", key)
				}
			}
		}
		if _, exists := seen[key]; exists {
			return fmt.Errorf("duplicate request field: %s", key)
		}
		seen[key] = struct{}{}
		var value json.RawMessage
		if err := decoder.Decode(&value); err != nil {
			return fmt.Errorf("invalid request field %s: %w", key, err)
		}
	}
	if token, err := decoder.Token(); err != nil {
		return fmt.Errorf("invalid request object: %w", err)
	} else if delimiter, ok := token.(json.Delim); !ok || delimiter != '}' {
		return errors.New("request object not closed")
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != io.EOF {
		if err == nil {
			return errors.New("multiple JSON values")
		}
		return fmt.Errorf("invalid trailing JSON: %w", err)
	}
	return nil
}

func validatePrototypeRunRequest(request prototypeRunRequest) (PrototypeSchedule, error) {
	if request.ProtocolVersion != "prototype-stream-0.1" {
		return PrototypeSchedule{}, prototypeIncompatibleError("unsupported protocol_version")
	}
	if !validPrototypeID(request.CampaignID) || !validPrototypeID(request.RunID) {
		return PrototypeSchedule{}, errors.New("campaign_id and run_id must use the contract identifier grammar")
	}
	if request.CampaignMode != "quick" && request.CampaignMode != "acceptance" {
		return PrototypeSchedule{}, errors.New("unsupported campaign_mode")
	}
	if request.WorkloadID != "streaming_text_reference_v0.1" || request.WorkloadVersion != "0.1" {
		return PrototypeSchedule{}, prototypeIncompatibleError("workload identity mismatch")
	}
	if request.ProfileID != "streaming_text_reference_v0.1" || request.ProfileVersion != "0.1" {
		return PrototypeSchedule{}, prototypeIncompatibleError("profile identity mismatch")
	}
	if request.ProfileManifestSHA256 != prototypeProfileManifestSHA256 {
		return PrototypeSchedule{}, prototypeIncompatibleError("profile manifest mismatch")
	}
	if request.ConditionVersion != "0.1" {
		return PrototypeSchedule{}, prototypeIncompatibleError("condition version mismatch")
	}
	if !validPrototypeRunIndex(request.CampaignMode, request.RunIndex, request.ConditionID) {
		return PrototypeSchedule{}, prototypeIncompatibleError("run index and condition mismatch")
	}
	schedule, err := GeneratePrototypeSchedule(request.ConditionID)
	if err != nil {
		return PrototypeSchedule{}, prototypeIncompatibleError(err.Error())
	}
	return schedule, nil
}

func (a *app) reservePrototypeRun(request prototypeRunRequest, schedule PrototypeSchedule) bool {
	identity := prototypeRunIdentity{
		CampaignID:            request.CampaignID,
		CampaignMode:          request.CampaignMode,
		RunIndex:              request.RunIndex,
		WorkloadID:            request.WorkloadID,
		WorkloadVersion:       request.WorkloadVersion,
		ProfileID:             request.ProfileID,
		ProfileVersion:        request.ProfileVersion,
		ProfileManifestSHA256: request.ProfileManifestSHA256,
		ConditionID:           request.ConditionID,
		ConditionVersion:      request.ConditionVersion,
		ScheduleHash:          schedule.ScheduleHash,
	}
	a.prototypeRunsMu.Lock()
	defer a.prototypeRunsMu.Unlock()
	if a.prototypeRuns == nil {
		a.prototypeRuns = make(map[string]prototypeRunIdentity)
	}
	if _, exists := a.prototypeRuns[request.RunID]; exists {
		return false
	}
	a.prototypeRuns[request.RunID] = identity
	return true
}

func (a *app) releasePrototypeRun(runID string) {
	a.prototypeRunsMu.Lock()
	defer a.prototypeRunsMu.Unlock()
	if a.prototypeRuns == nil {
		return
	}
	delete(a.prototypeRuns, runID)
	if len(a.prototypeRuns) == 0 {
		a.prototypeRuns = nil
	}
}

func validPrototypeRunIndex(mode string, index int, conditionID string) bool {
	if mode == "quick" {
		return (index == 1 && conditionID == "baseline_v0.1") ||
			(index == 2 && conditionID == "slow_v0.1") ||
			(index == 3 && conditionID == "unstable_v0.1")
	}
	if index < 1 || index > 9 {
		return false
	}
	conditions := []string{"baseline_v0.1", "slow_v0.1", "unstable_v0.1"}
	return conditionID == conditions[(index-1)%3]
}

func validPrototypeID(value string) bool {
	if len(value) < 8 || len(value) > 128 {
		return false
	}
	for _, r := range value {
		if !strings.ContainsRune("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz._:-", r) {
			return false
		}
	}
	return true
}

func waitPrototypeOffset(ctx context.Context, start time.Time, offsetMs int64, sleep prototypeSleepFunc, now prototypeNowFunc) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	current := time.Now()
	if now != nil {
		current = now()
	}
	remaining := start.Add(time.Duration(offsetMs) * time.Millisecond).Sub(current)
	if remaining <= 0 {
		return nil
	}
	if sleep != nil {
		return sleep(ctx, remaining)
	}
	timer := time.NewTimer(remaining)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func writePrototypeFailure(w http.ResponseWriter, flusher http.Flusher, request prototypeRunRequest, eventsReceived int, err error) error {
	reason := "stream_interrupted"
	eventName := "run_failed"
	if errors.Is(err, context.Canceled) {
		reason = "cancelled"
		eventName = "run_cancelled"
	}
	return writePrototypeSSE(w, flusher, eventName, prototypeSSEEnvelope{
		SchemaVersion:     "aneb-prototype-evidence-0.1",
		ProtocolVersion:   "prototype-stream-0.1",
		CampaignID:        request.CampaignID,
		RunID:             request.RunID,
		ConditionID:       request.ConditionID,
		EventType:         eventName,
		ServerMonotonicNs: time.Since(procStart).Nanoseconds(),
		ClockSource:       "server.monotonic",
		ClockUnit:         "ns",
		ClockEpoch:        "process",
		Source:            "server",
		Details: prototypeFailureDetails{
			FailureReason:  reason,
			EventsReceived: eventsReceived,
		},
	})
}

func writePrototypeSSE(w http.ResponseWriter, flusher http.Flusher, eventName string, value prototypeSSEEnvelope) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	if _, err := fmt.Fprintf(w, "event: %s\ndata: %s\n\n", eventName, data); err != nil {
		return err
	}
	flusher.Flush()
	return nil
}

func writePrototypeJSONError(w http.ResponseWriter, status int, code, reason string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": code, "reason": reason})
}
