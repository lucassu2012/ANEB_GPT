package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestPrototypeRunRejectsClientTimingOverrideBeforeSSE(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	body := `{"protocol_version":"prototype-stream-0.1","campaign_id":"00000000-0000-0000-0000-000000000001","run_id":"00000000-0000-0000-0000-000000000002","campaign_mode":"quick","run_index":1,"profile_id":"streaming_text_reference_v0.1","profile_version":"0.1","profile_manifest_sha256":"44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc","condition_id":"baseline_v0.1","condition_version":"0.1","rate_tps":10}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, body = %s", res.Code, res.Body.String())
	}
	if strings.Contains(res.Header().Get("Content-Type"), "text/event-stream") {
		t.Fatalf("rejected request advertised SSE")
	}
	if !strings.Contains(res.Body.String(), "server_rejected") {
		t.Fatalf("body = %q, want server_rejected", res.Body.String())
	}
}

func TestPrototypeRunStreamsCanonicalContentAndTerminalReceipt(t *testing.T) {
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(context.Context, time.Duration) error {
			return nil
		},
	}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBody(t)))
	req.Header.Set("Content-Type", "application/json")
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", res.Code, res.Body.String())
	}
	if got := res.Header().Get("Content-Type"); got != "text/event-stream" {
		t.Fatalf("content type = %q", got)
	}

	frames := strings.Split(strings.TrimSuffix(res.Body.String(), "\n\n"), "\n\n")
	if len(frames) != 122 {
		t.Fatalf("frame count = %d, want 122", len(frames))
	}
	if !strings.HasPrefix(frames[0], "event: run_started\ndata: ") {
		t.Fatalf("first frame = %q", frames[0])
	}
	seen := 0
	for i := 1; i <= 120; i++ {
		prefix := "event: content_event\ndata: "
		if !strings.HasPrefix(frames[i], prefix) {
			t.Fatalf("frame %d = %q", i, frames[i])
		}
		var envelope map[string]any
		if err := json.Unmarshal([]byte(strings.TrimPrefix(frames[i], prefix)), &envelope); err != nil {
			t.Fatalf("content frame %d: %v", i, err)
		}
		if envelope["campaign_id"] != "00000000-0000-0000-0000-000000000001" || envelope["run_id"] != "00000000-0000-0000-0000-000000000002" || envelope["condition_id"] != "baseline_v0.1" {
			t.Fatalf("content identity %d = %#v", i, envelope)
		}
		details, ok := envelope["details"].(map[string]any)
		if !ok || details["seq"] != float64(i) || details["payload_id"] != fmt.Sprintf("ref-%04d", i) {
			t.Fatalf("content details %d = %#v", i, envelope["details"])
		}
		seen++
	}
	if seen != 120 {
		t.Fatalf("content count = %d", seen)
	}
	if !strings.HasPrefix(frames[121], "event: done\ndata: ") {
		t.Fatalf("terminal frame = %q", frames[121])
	}
	var terminal map[string]any
	if err := json.Unmarshal([]byte(strings.TrimPrefix(frames[121], "event: done\ndata: ")), &terminal); err != nil {
		t.Fatalf("terminal frame: %v", err)
	}
	if terminal["event_type"] != "terminal_event" || terminal["campaign_id"] != "00000000-0000-0000-0000-000000000001" || terminal["run_id"] != "00000000-0000-0000-0000-000000000002" {
		t.Fatalf("terminal envelope = %#v", terminal)
	}
	receipt, ok := terminal["details"].(map[string]any)
	if !ok {
		t.Fatalf("terminal details = %#v", terminal["details"])
	}
	for key, want := range map[string]any{
		"protocol_version":        "prototype-stream-0.1",
		"campaign_mode":           "quick",
		"run_index":               float64(1),
		"condition_id":            "baseline_v0.1",
		"condition_version":       "0.1",
		"profile_id":              "streaming_text_reference_v0.1",
		"profile_version":         "0.1",
		"profile_manifest_sha256": "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc",
		"schedule_hash":           "46eced73d2fbc886040a3357f84551d424a95e15d6e9e69c16958f6e52e33d7e",
		"nominal_interval_ms":     float64(50),
		"terminal_status":         "complete",
		"planned_event_count":     float64(120),
		"emitted_event_count":     float64(120),
	} {
		if receipt[key] != want {
			t.Fatalf("receipt %s = %#v, want %#v", key, receipt[key], want)
		}
	}
}

func TestPrototypeRunTerminalReceiptBindsRequestIDs(t *testing.T) {
	const campaignID = "campaign-alt-ids-01"
	const runID = "run-alt-ids-01"
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(context.Context, time.Duration) error {
			return nil
		},
	}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBodyWithIDs(t, campaignID, runID)))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", res.Code, res.Body.String())
	}
	frames := strings.Split(strings.TrimSuffix(res.Body.String(), "\n\n"), "\n\n")
	if len(frames) != 122 || !strings.HasPrefix(frames[len(frames)-1], "event: done\ndata: ") {
		t.Fatalf("terminal frame shape: count=%d last=%q", len(frames), frames[len(frames)-1])
	}
	var terminal map[string]any
	if err := json.Unmarshal([]byte(strings.TrimPrefix(frames[len(frames)-1], "event: done\ndata: ")), &terminal); err != nil {
		t.Fatalf("terminal frame: %v", err)
	}
	if terminal["campaign_id"] != campaignID || terminal["run_id"] != runID {
		t.Fatalf("terminal envelope IDs = campaign=%#v run=%#v", terminal["campaign_id"], terminal["run_id"])
	}
	receipt, ok := terminal["details"].(map[string]any)
	if !ok {
		t.Fatalf("terminal details = %#v", terminal["details"])
	}
	if receipt["campaign_id"] != campaignID || receipt["run_id"] != runID {
		t.Fatalf("terminal receipt IDs = campaign=%#v run=%#v", receipt["campaign_id"], receipt["run_id"])
	}
}

func TestPrototypeRunStreamsGeneratedScheduleForEveryCondition(t *testing.T) {
	cases := []struct {
		name         string
		campaignMode string
		conditionID  string
		runIndex     int
	}{
		{name: "quick-baseline-index1", campaignMode: "quick", conditionID: "baseline_v0.1", runIndex: 1},
		{name: "quick-slow-index2", campaignMode: "quick", conditionID: "slow_v0.1", runIndex: 2},
		{name: "quick-unstable-index3", campaignMode: "quick", conditionID: "unstable_v0.1", runIndex: 3},
		{name: "acceptance-baseline-index4", campaignMode: "acceptance", conditionID: "baseline_v0.1", runIndex: 4},
		{name: "acceptance-unstable-index9", campaignMode: "acceptance", conditionID: "unstable_v0.1", runIndex: 9},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			schedule, err := GeneratePrototypeSchedule(tc.conditionID)
			if err != nil {
				t.Fatalf("schedule: %v", err)
			}
			current := time.Unix(0, 0)
			campaignID := "campaign-" + tc.name
			runID := "run-" + tc.name
			a := &app{
				profiles: map[string]*Profile{},
				dataDir:  t.TempDir(),
				prototypeNow: func() time.Time {
					return current
				},
				prototypeSleep: func(_ context.Context, duration time.Duration) error {
					current = current.Add(duration)
					return nil
				},
			}
			body := prototypeValidRunBodyForModeAndCondition(t, campaignID, runID, tc.campaignMode, tc.conditionID, tc.runIndex)
			req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(body))
			res := httptest.NewRecorder()
			a.routes().ServeHTTP(res, req)
			if res.Code != http.StatusOK {
				t.Fatalf("status = %d, body = %s", res.Code, res.Body.String())
			}
			frames := strings.Split(strings.TrimSuffix(res.Body.String(), "\n\n"), "\n\n")
			if len(frames) != len(schedule.Events)+2 {
				t.Fatalf("frame count = %d, want %d", len(frames), len(schedule.Events)+2)
			}
			for i, event := range schedule.Events {
				prefix := "event: content_event\ndata: "
				frame := frames[i+1]
				if !strings.HasPrefix(frame, prefix) {
					t.Fatalf("content frame %d = %q", i+1, frame)
				}
				var envelope map[string]any
				if err := json.Unmarshal([]byte(strings.TrimPrefix(frame, prefix)), &envelope); err != nil {
					t.Fatalf("content frame %d: %v", i+1, err)
				}
				if envelope["campaign_id"] != campaignID || envelope["run_id"] != runID || envelope["condition_id"] != tc.conditionID {
					t.Fatalf("content identity %d = %#v", i+1, envelope)
				}
				details, ok := envelope["details"].(map[string]any)
				if !ok {
					t.Fatalf("content details %d = %#v", i+1, envelope["details"])
				}
				if details["seq"] != float64(event.Seq) || details["planned_offset_ms"] != float64(event.PlannedOffsetMs) || details["payload_id"] != event.PayloadID {
					t.Fatalf("content schedule %d = %#v, want seq=%d offset=%d payload=%s", i+1, details, event.Seq, event.PlannedOffsetMs, event.PayloadID)
				}
				if details["profile_manifest_sha256"] != prototypeProfileManifestSHA256 || details["schedule_hash"] != schedule.ScheduleHash {
					t.Fatalf("content metadata %d = profile=%#v schedule=%#v", i+1, details["profile_manifest_sha256"], details["schedule_hash"])
				}
			}
			last := frames[len(frames)-1]
			if !strings.HasPrefix(last, "event: done\ndata: ") {
				t.Fatalf("terminal frame = %q", last)
			}
			var terminal map[string]any
			if err := json.Unmarshal([]byte(strings.TrimPrefix(last, "event: done\ndata: ")), &terminal); err != nil {
				t.Fatalf("terminal frame: %v", err)
			}
			if terminal["event_type"] != "terminal_event" || terminal["campaign_id"] != campaignID || terminal["run_id"] != runID || terminal["condition_id"] != tc.conditionID {
				t.Fatalf("terminal envelope = %#v", terminal)
			}
			receipt, ok := terminal["details"].(map[string]any)
			if !ok {
				t.Fatalf("terminal details = %#v", terminal["details"])
			}
			expected := map[string]any{
				"protocol_version":        "prototype-stream-0.1",
				"campaign_id":             campaignID,
				"run_id":                  runID,
				"campaign_mode":           tc.campaignMode,
				"run_index":               float64(tc.runIndex),
				"condition_id":            tc.conditionID,
				"condition_version":       "0.1",
				"profile_id":              "streaming_text_reference_v0.1",
				"profile_version":         "0.1",
				"profile_manifest_sha256": prototypeProfileManifestSHA256,
				"schedule_hash":           schedule.ScheduleHash,
				"nominal_interval_ms":     float64(schedule.NominalIntervalMs),
				"planned_event_count":     float64(len(schedule.Events)),
				"emitted_event_count":     float64(len(schedule.Events)),
				"terminal_status":         "complete",
			}
			if len(receipt) != len(expected) {
				t.Fatalf("terminal receipt key count = %d, want %d: %#v", len(receipt), len(expected), receipt)
			}
			for key, want := range expected {
				if receipt[key] != want {
					t.Fatalf("terminal receipt %s = %#v, want %#v", key, receipt[key], want)
				}
			}
		})
	}
}

func TestPrototypeRunTerminalReceiptUsesExactServerWireKeyset(t *testing.T) {
	serverDetails, _, _ := loadPrototypeOptionATerminalProjectionFixture(t)
	campaignID := serverDetails["campaign_id"].(string)
	runID := serverDetails["run_id"].(string)
	campaignMode := serverDetails["campaign_mode"].(string)
	conditionID := serverDetails["condition_id"].(string)
	runIndex := int(serverDetails["run_index"].(float64))
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(context.Context, time.Duration) error {
			return nil
		},
	}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBodyForModeAndCondition(t, campaignID, runID, campaignMode, conditionID, runIndex)))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", res.Code, res.Body.String())
	}
	frames := strings.Split(strings.TrimSuffix(res.Body.String(), "\n\n"), "\n\n")
	if len(frames) != 122 || !strings.HasPrefix(frames[len(frames)-1], "event: done\ndata: ") {
		t.Fatalf("terminal frame shape: count=%d last=%q", len(frames), frames[len(frames)-1])
	}
	var envelope map[string]any
	if err := json.Unmarshal([]byte(strings.TrimPrefix(frames[len(frames)-1], "event: done\ndata: ")), &envelope); err != nil {
		t.Fatalf("terminal envelope: %v", err)
	}
	receipt, ok := envelope["details"].(map[string]any)
	if !ok {
		t.Fatalf("terminal details = %#v", envelope["details"])
	}
	expectedKeys := map[string]struct{}{
		"protocol_version": {}, "campaign_id": {}, "run_id": {},
		"campaign_mode": {}, "run_index": {},
		"condition_id": {}, "condition_version": {}, "profile_id": {}, "profile_version": {},
		"profile_manifest_sha256": {}, "schedule_hash": {}, "nominal_interval_ms": {},
		"planned_event_count": {}, "emitted_event_count": {}, "terminal_status": {},
	}
	if len(receipt) != len(expectedKeys) {
		t.Fatalf("terminal receipt key count = %d, want %d: %#v", len(receipt), len(expectedKeys), receipt)
	}
	for key := range receipt {
		if _, ok := expectedKeys[key]; !ok {
			t.Fatalf("unexpected terminal receipt key %q", key)
		}
	}
	for key := range expectedKeys {
		if _, ok := receipt[key]; !ok {
			t.Fatalf("missing terminal receipt key %q", key)
		}
	}
	for _, removed := range []string{"receipt_version", "canonical_receipt_version", "workload_id", "workload_version"} {
		if _, ok := receipt[removed]; ok {
			t.Fatalf("removed non-wire field %q still present", removed)
		}
	}
	if receipt["campaign_mode"] != campaignMode || receipt["run_index"] != float64(runIndex) {
		t.Fatalf("request identity missing from receipt: mode=%#v index=%#v", receipt["campaign_mode"], receipt["run_index"])
	}
	for key, want := range serverDetails {
		if !reflect.DeepEqual(receipt[key], want) {
			t.Fatalf("fixture receipt %s = %#v, want %#v", key, receipt[key], want)
		}
	}
	for _, forbidden := range []string{
		"clock_domain_id", "clock_source", "clock_unit", "clock_epoch", "t0_monotonic_ns",
		"server_monotonic_ns", "events_expected", "events_received", "client_monotonic_ns",
		"client_events_received", "android_events_received", "received_count",
	} {
		if _, ok := receipt[forbidden]; ok {
			t.Fatalf("client-enriched field leaked into server receipt: %s", forbidden)
		}
	}
}

func loadPrototypeOptionATerminalProjectionFixture(t *testing.T) (map[string]any, map[string]any, map[string]any) {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("testdata", "prototype_option_a_terminal_projection.json"))
	if err != nil {
		t.Fatalf("read Option A projection fixture: %v", err)
	}
	var fixture map[string]any
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatalf("decode Option A projection fixture: %v", err)
	}
	var typed struct {
		ServerDoneDetails struct {
			RunIndex          int   `json:"run_index"`
			NominalIntervalMs int64 `json:"nominal_interval_ms"`
			PlannedEventCount int   `json:"planned_event_count"`
			EmittedEventCount int   `json:"emitted_event_count"`
		} `json:"server_done_details"`
		AndroidAdditions struct {
			ReceiptVersion    string `json:"receipt_version"`
			EventsExpected    int    `json:"events_expected"`
			EventsReceived    int    `json:"events_received"`
			ClockDomainID     string `json:"clock_domain_id"`
			ClockSource       string `json:"clock_source"`
			ClockUnit         string `json:"clock_unit"`
			ClockEpoch        string `json:"clock_epoch"`
			T0MonotonicNs     int64  `json:"t0_monotonic_ns"`
			ClientMonotonicNs int64  `json:"client_monotonic_ns"`
		} `json:"android_additions"`
		CanonicalTerminalEventDetails struct {
			RunIndex          int   `json:"run_index"`
			NominalIntervalMs int64 `json:"nominal_interval_ms"`
			PlannedEventCount int   `json:"planned_event_count"`
			EmittedEventCount int   `json:"emitted_event_count"`
			EventsExpected    int   `json:"events_expected"`
			EventsReceived    int   `json:"events_received"`
			T0MonotonicNs     int64 `json:"t0_monotonic_ns"`
			ClientMonotonicNs int64 `json:"client_monotonic_ns"`
		} `json:"canonical_terminal_event_details"`
	}
	if err := json.Unmarshal(raw, &typed); err != nil {
		t.Fatalf("decode typed Android additions: %v", err)
	}
	if fixture["fixture_version"] != "prototype-server-android-terminal-projection-0.1" {
		t.Fatalf("fixture_version = %#v", fixture["fixture_version"])
	}
	server, ok := fixture["server_done_details"].(map[string]any)
	if !ok {
		t.Fatalf("server_done_details = %#v", fixture["server_done_details"])
	}
	android, ok := fixture["android_additions"].(map[string]any)
	if !ok {
		t.Fatalf("android_additions = %#v", fixture["android_additions"])
	}
	canonical, ok := fixture["canonical_terminal_event_details"].(map[string]any)
	if !ok {
		t.Fatalf("canonical_terminal_event_details = %#v", fixture["canonical_terminal_event_details"])
	}
	serverKeys := map[string]struct{}{
		"protocol_version": {}, "campaign_id": {}, "run_id": {}, "campaign_mode": {}, "run_index": {},
		"condition_id": {}, "condition_version": {}, "profile_id": {}, "profile_version": {},
		"profile_manifest_sha256": {}, "schedule_hash": {}, "nominal_interval_ms": {},
		"planned_event_count": {}, "emitted_event_count": {}, "terminal_status": {},
	}
	androidKeys := map[string]struct{}{
		"receipt_version": {}, "events_expected": {}, "events_received": {}, "clock_domain_id": {},
		"clock_source": {}, "clock_unit": {}, "clock_epoch": {}, "t0_monotonic_ns": {}, "client_monotonic_ns": {},
	}
	if len(server) != len(serverKeys) || len(android) != len(androidKeys) {
		t.Fatalf("fixture key counts server=%d android=%d", len(server), len(android))
	}
	for key := range server {
		if _, ok := serverKeys[key]; !ok {
			t.Fatalf("unexpected server fixture key %q", key)
		}
	}
	for key := range android {
		if _, ok := androidKeys[key]; !ok {
			t.Fatalf("unexpected Android fixture key %q", key)
		}
	}
	expectedAndroid := map[string]any{
		"receipt_version":     "prototype-terminal-receipt-0.1",
		"events_expected":     float64(120),
		"events_received":     float64(120),
		"clock_source":        "android.os.SystemClock.elapsedRealtimeNanos",
		"clock_unit":          "ns",
		"clock_epoch":         "device_boot",
		"t0_monotonic_ns":     float64(1000000000),
		"client_monotonic_ns": float64(12300000000),
	}
	for key, want := range expectedAndroid {
		if got, ok := android[key]; !ok || !reflect.DeepEqual(got, want) {
			t.Fatalf("Android fixture %s = %#v (%T), want %#v (%T)", key, got, got, want, want)
		}
	}
	clockDomain, ok := android["clock_domain_id"].(string)
	if !ok || clockDomain == "" {
		t.Fatalf("clock_domain_id = %#v (%T), want non-empty string", android["clock_domain_id"], android["clock_domain_id"])
	}
	if typed.AndroidAdditions.ReceiptVersion != "prototype-terminal-receipt-0.1" ||
		typed.AndroidAdditions.EventsExpected != 120 || typed.AndroidAdditions.EventsReceived != 120 ||
		typed.AndroidAdditions.ClockSource != "android.os.SystemClock.elapsedRealtimeNanos" ||
		typed.AndroidAdditions.ClockUnit != "ns" || typed.AndroidAdditions.ClockEpoch != "device_boot" ||
		typed.AndroidAdditions.T0MonotonicNs != 1000000000 || typed.AndroidAdditions.ClientMonotonicNs != 12300000000 ||
		typed.AndroidAdditions.ClientMonotonicNs < typed.AndroidAdditions.T0MonotonicNs {
		t.Fatalf("typed Android additions = %#v", typed.AndroidAdditions)
	}
	if typed.ServerDoneDetails.RunIndex != 1 || typed.ServerDoneDetails.NominalIntervalMs != 50 ||
		typed.ServerDoneDetails.PlannedEventCount != 120 || typed.ServerDoneDetails.EmittedEventCount != 120 {
		t.Fatalf("typed server integer fields = %#v", typed.ServerDoneDetails)
	}
	if typed.CanonicalTerminalEventDetails.RunIndex != typed.ServerDoneDetails.RunIndex ||
		typed.CanonicalTerminalEventDetails.NominalIntervalMs != typed.ServerDoneDetails.NominalIntervalMs ||
		typed.CanonicalTerminalEventDetails.PlannedEventCount != typed.ServerDoneDetails.PlannedEventCount ||
		typed.CanonicalTerminalEventDetails.EmittedEventCount != typed.ServerDoneDetails.EmittedEventCount ||
		typed.CanonicalTerminalEventDetails.EventsExpected != typed.AndroidAdditions.EventsExpected ||
		typed.CanonicalTerminalEventDetails.EventsReceived != typed.AndroidAdditions.EventsReceived ||
		typed.CanonicalTerminalEventDetails.T0MonotonicNs != typed.AndroidAdditions.T0MonotonicNs ||
		typed.CanonicalTerminalEventDetails.ClientMonotonicNs != typed.AndroidAdditions.ClientMonotonicNs {
		t.Fatalf("typed canonical integer fields = %#v", typed.CanonicalTerminalEventDetails)
	}
	if len(canonical) != len(serverKeys)+len(androidKeys) {
		t.Fatalf("canonical fixture key count = %d, want %d", len(canonical), len(serverKeys)+len(androidKeys))
	}
	for key, want := range server {
		if got, ok := canonical[key]; !ok || !reflect.DeepEqual(got, want) {
			t.Fatalf("canonical server projection %s = %#v, want %#v", key, got, want)
		}
	}
	for key, want := range android {
		if got, ok := canonical[key]; !ok || !reflect.DeepEqual(got, want) {
			t.Fatalf("canonical Android projection %s = %#v, want %#v", key, got, want)
		}
	}
	return server, android, canonical
}

func TestPrototypeRunCancellationHasNoDoneReceipt(t *testing.T) {
	started := make(chan struct{})
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(ctx context.Context, _ time.Duration) error {
			select {
			case <-started:
			default:
				close(started)
			}
			<-ctx.Done()
			return ctx.Err()
		},
	}
	ctx, cancel := context.WithCancel(context.Background())
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBody(t)))
	req = req.WithContext(ctx)
	res := httptest.NewRecorder()
	done := make(chan struct{})
	go func() {
		a.routes().ServeHTTP(res, req)
		close(done)
	}()
	<-started
	cancel()
	<-done
	if res.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", res.Code, res.Body.String())
	}
	body := res.Body.String()
	if !strings.Contains(body, "event: run_cancelled") || !strings.Contains(body, `"failure_reason":"cancelled"`) {
		t.Fatalf("cancellation body = %q", body)
	}
	if strings.Contains(body, "event: done") {
		t.Fatalf("cancelled stream emitted terminal done: %q", body)
	}
}

func prototypeValidRunBody(t *testing.T) string {
	t.Helper()
	return `{"protocol_version":"prototype-stream-0.1","campaign_id":"00000000-0000-0000-0000-000000000001","run_id":"00000000-0000-0000-0000-000000000002","campaign_mode":"quick","run_index":1,"workload_id":"streaming_text_reference_v0.1","workload_version":"0.1","profile_id":"streaming_text_reference_v0.1","profile_version":"0.1","profile_manifest_sha256":"44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc","condition_id":"baseline_v0.1","condition_version":"0.1"}`
}

func TestPrototypeRunDoneWaitsForPlannedTerminalOffsetAndIsLast(t *testing.T) {
	current := time.Unix(0, 0)
	sleeps := make([]time.Duration, 0, 121)
	a := &app{
		profiles:     map[string]*Profile{},
		dataDir:      t.TempDir(),
		prototypeNow: func() time.Time { return current },
		prototypeSleep: func(_ context.Context, duration time.Duration) error {
			sleeps = append(sleeps, duration)
			current = current.Add(duration)
			return nil
		},
	}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBody(t)))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", res.Code, res.Body.String())
	}
	if len(sleeps) != 121 {
		t.Fatalf("sleep calls = %d, want 121 (120 events + terminal delay)", len(sleeps))
	}
	if sleeps[0] != 200*time.Millisecond || sleeps[119] != 50*time.Millisecond || sleeps[120] != 50*time.Millisecond {
		t.Fatalf("planned waits = first %s/event120 %s/terminal %s", sleeps[0], sleeps[119], sleeps[120])
	}
	frames := strings.Split(strings.TrimSuffix(res.Body.String(), "\n\n"), "\n\n")
	if len(frames) != 122 || !strings.HasPrefix(frames[len(frames)-1], "event: done\ndata: ") {
		t.Fatalf("terminal is not the final frame: count=%d last=%q", len(frames), frames[len(frames)-1])
	}
}

func TestPrototypeRunCancellationAfterContentBeforeTerminalHasNoDone(t *testing.T) {
	current := time.Unix(0, 0)
	terminalWait := make(chan struct{})
	sleepCalls := 0
	a := &app{
		profiles:     map[string]*Profile{},
		dataDir:      t.TempDir(),
		prototypeNow: func() time.Time { return current },
		prototypeSleep: func(ctx context.Context, duration time.Duration) error {
			sleepCalls++
			if sleepCalls <= 120 {
				current = current.Add(duration)
				return nil
			}
			close(terminalWait)
			<-ctx.Done()
			return ctx.Err()
		},
	}
	ctx, cancel := context.WithCancel(context.Background())
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBody(t))).WithContext(ctx)
	res := httptest.NewRecorder()
	done := make(chan struct{})
	go func() {
		a.routes().ServeHTTP(res, req)
		close(done)
	}()
	// The terminal wait is the 121st sleep. The first 120 return immediately;
	// this barrier makes cancellation happen only after content 120 was sent.
	<-terminalWait
	cancel()
	<-done
	body := res.Body.String()
	if strings.Count(body, "event: content_event") != 120 || strings.Contains(body, "event: done") {
		t.Fatalf("post-content cancellation body has content=%d done=%t", strings.Count(body, "event: content_event"), strings.Contains(body, "event: done"))
	}
}

func TestPrototypeRunTerminalWaitErrorHasNoDone(t *testing.T) {
	current := time.Unix(0, 0)
	sleeps := 0
	a := &app{
		profiles:     map[string]*Profile{},
		dataDir:      t.TempDir(),
		prototypeNow: func() time.Time { return current },
		prototypeSleep: func(_ context.Context, duration time.Duration) error {
			sleeps++
			if sleeps == 121 {
				return errors.New("terminal writer unavailable")
			}
			current = current.Add(duration)
			return nil
		},
	}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBody(t)))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	body := res.Body.String()
	if strings.Contains(body, "event: done") || !strings.Contains(body, "event: run_failed") || !strings.Contains(body, `"failure_reason":"stream_interrupted"`) {
		t.Fatalf("terminal wait error body = %q", body)
	}
	frames := strings.Split(strings.TrimSuffix(body, "\n\n"), "\n\n")
	var failure map[string]any
	if err := json.Unmarshal([]byte(strings.TrimPrefix(frames[len(frames)-1], "event: run_failed\ndata: ")), &failure); err != nil {
		t.Fatalf("failure frame: %v", err)
	}
	if failure["event_type"] != "run_failed" {
		t.Fatalf("failure event_type = %#v", failure["event_type"])
	}
}

func TestPrototypeRunRejectsDuplicateIdentityFieldBeforeSSE(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	body := `{"protocol_version":"prototype-stream-0.1","campaign_id":"00000000-0000-0000-0000-000000000001","run_id":"00000000-0000-0000-0000-000000000002","campaign_mode":"quick","run_index":1,"profile_id":"streaming_text_reference_v0.1","profile_version":"0.1","profile_manifest_sha256":"44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc","condition_id":"baseline_v0.1","condition_id":"baseline_v0.1","condition_version":"0.1"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(body))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusBadRequest || strings.Contains(res.Header().Get("Content-Type"), "text/event-stream") {
		t.Fatalf("duplicate field status=%d type=%q body=%s", res.Code, res.Header().Get("Content-Type"), res.Body.String())
	}
}

func TestPrototypeRunReportsIncompatibleIdentityBeforeSSE(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	body := strings.Replace(prototypeValidRunBody(t), `"condition_id":"baseline_v0.1"`, `"condition_id":"slow_v0.1"`, 1)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(body))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusConflict {
		t.Fatalf("status = %d, body = %s", res.Code, res.Body.String())
	}
	if strings.Contains(res.Header().Get("Content-Type"), "text/event-stream") || !strings.Contains(res.Body.String(), "incompatible") {
		t.Fatalf("incompatible response type=%q body=%s", res.Header().Get("Content-Type"), res.Body.String())
	}
}

func TestPrototypeRunRejectsTimingQueryBeforeSSE(t *testing.T) {
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(context.Context, time.Duration) error {
			return nil
		},
	}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs?rate_tps=10", strings.NewReader(prototypeValidRunBody(t)))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusBadRequest || strings.Contains(res.Header().Get("Content-Type"), "text/event-stream") {
		t.Fatalf("timing query status=%d type=%q body=%s", res.Code, res.Header().Get("Content-Type"), res.Body.String())
	}
	if !strings.Contains(res.Body.String(), "server_rejected") {
		t.Fatalf("timing query body = %q", res.Body.String())
	}
}

func TestPrototypeRunAcceptsContractIdentifiersAndWorkloadFields(t *testing.T) {
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(context.Context, time.Duration) error {
			return nil
		},
	}
	body := `{"protocol_version":"prototype-stream-0.1","campaign_id":"campaign-0001","run_id":"run-quick-01","campaign_mode":"quick","run_index":1,"workload_id":"streaming_text_reference_v0.1","workload_version":"0.1","profile_id":"streaming_text_reference_v0.1","profile_version":"0.1","profile_manifest_sha256":"44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc","condition_id":"baseline_v0.1","condition_version":"0.1"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(body))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusOK || !strings.Contains(res.Body.String(), "event: done") {
		t.Fatalf("contract request status=%d body=%s", res.Code, res.Body.String())
	}
}

func TestPrototypeRunRejectsWorkloadMismatchBeforeSSE(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	body := strings.Replace(prototypeValidRunBody(t), `"workload_id":"streaming_text_reference_v0.1"`, `"workload_id":"other_workload"`, 1)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(body))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusConflict || strings.Contains(res.Header().Get("Content-Type"), "text/event-stream") || !strings.Contains(res.Body.String(), "incompatible") {
		t.Fatalf("workload mismatch status=%d type=%q body=%s", res.Code, res.Header().Get("Content-Type"), res.Body.String())
	}
}

func TestPrototypeRunRejectsConcurrentRunIDConflictBeforeSSE(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	var startOnce sync.Once
	var sleepCalls atomic.Int32
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(ctx context.Context, _ time.Duration) error {
			call := sleepCalls.Add(1)
			if call == 1 {
				startOnce.Do(func() { close(started) })
			} else {
				// If the registry is missing, the second request must complete
				// rather than hanging the oracle before it can report a false
				// business stream.
				return nil
			}
			select {
			case <-release:
				return nil
			case <-ctx.Done():
				return ctx.Err()
			}
		},
	}
	firstCtx, cancelFirst := context.WithCancel(context.Background())
	firstReq := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBody(t))).WithContext(firstCtx)
	firstRes := httptest.NewRecorder()
	firstDone := make(chan struct{})
	go func() {
		a.routes().ServeHTTP(firstRes, firstReq)
		close(firstDone)
	}()
	<-started

	secondBody := strings.Replace(prototypeValidRunBody(t), `"campaign_id":"00000000-0000-0000-0000-000000000001"`, `"campaign_id":"campaign-0002"`, 1)
	secondBody = strings.Replace(secondBody, `"condition_id":"baseline_v0.1"`, `"condition_id":"slow_v0.1"`, 1)
	secondBody = strings.Replace(secondBody, `"run_index":1`, `"run_index":2`, 1)
	secondReq := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(secondBody))
	secondRes := httptest.NewRecorder()
	a.routes().ServeHTTP(secondRes, secondReq)

	if secondRes.Code != http.StatusConflict {
		t.Fatalf("conflict status = %d, body = %s", secondRes.Code, secondRes.Body.String())
	}
	if strings.Contains(secondRes.Header().Get("Content-Type"), "text/event-stream") || strings.Contains(secondRes.Body.String(), "event:") {
		t.Fatalf("conflict response emitted business SSE: type=%q body=%s", secondRes.Header().Get("Content-Type"), secondRes.Body.String())
	}
	if !strings.Contains(secondRes.Body.String(), "run_conflict") {
		t.Fatalf("conflict body = %q, want run_conflict", secondRes.Body.String())
	}
	cancelFirst()
	<-firstDone
	if len(a.prototypeRuns) != 0 {
		t.Fatalf("active run registry after cancellation = %d, want 0", len(a.prototypeRuns))
	}
	freshBody := prototypeValidRunBodyWithIDs(t, "campaign-0002", "run-quick-03")
	thirdReq := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(freshBody))
	thirdRes := httptest.NewRecorder()
	a.routes().ServeHTTP(thirdRes, thirdReq)
	if thirdRes.Code != http.StatusOK || !strings.Contains(thirdRes.Body.String(), "event: done") {
		t.Fatalf("fresh run-id liveness status=%d body=%s", thirdRes.Code, thirdRes.Body.String())
	}
	if len(a.prototypeRuns) != 0 {
		t.Fatalf("active run registry after fresh run-id liveness = %d, want 0", len(a.prototypeRuns))
	}
}

func TestPrototypeRunTerminalWireReceiptUsesExplicitServerOnlySchema(t *testing.T) {
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(context.Context, time.Duration) error {
			return nil
		},
	}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBody(t)))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", res.Code, res.Body.String())
	}
	frames := strings.Split(strings.TrimSuffix(res.Body.String(), "\n\n"), "\n\n")
	var envelope map[string]any
	if err := json.Unmarshal([]byte(strings.TrimPrefix(frames[len(frames)-1], "event: done\ndata: ")), &envelope); err != nil {
		t.Fatalf("terminal envelope: %v", err)
	}
	receipt, ok := envelope["details"].(map[string]any)
	if !ok {
		t.Fatalf("terminal details = %#v", envelope["details"])
	}
	expectedKeys := map[string]struct{}{
		"protocol_version": {}, "campaign_id": {}, "run_id": {},
		"campaign_mode": {}, "run_index": {},
		"condition_id": {}, "condition_version": {}, "profile_id": {}, "profile_version": {},
		"profile_manifest_sha256": {}, "schedule_hash": {}, "nominal_interval_ms": {},
		"planned_event_count": {}, "emitted_event_count": {}, "terminal_status": {},
	}
	if len(receipt) != len(expectedKeys) {
		t.Fatalf("server wire receipt key count = %d, want %d: %#v", len(receipt), len(expectedKeys), receipt)
	}
	for key := range receipt {
		if _, ok := expectedKeys[key]; !ok {
			t.Fatalf("unexpected server wire receipt key %q", key)
		}
	}
	if receipt["protocol_version"] != "prototype-stream-0.1" {
		t.Fatalf("protocol_version = %#v, want protocol-governed wire version", receipt["protocol_version"])
	}
	for _, removed := range []string{"receipt_version", "canonical_receipt_version", "workload_id", "workload_version"} {
		if _, ok := receipt[removed]; ok {
			t.Fatalf("removed non-wire field %q still present", removed)
		}
	}
	for _, forbidden := range []string{
		"clock_domain_id", "clock_source", "clock_unit", "clock_epoch", "t0_monotonic_ns",
		"server_monotonic_ns", "events_expected", "events_received", "client_monotonic_ns",
		"client_events_received", "android_events_received", "received_count",
	} {
		if _, ok := receipt[forbidden]; ok {
			t.Fatalf("server-only wire receipt leaked ambiguous/client field: %s", forbidden)
		}
	}
}

func TestPrototypeRunRejectsCaseVariantRequestKeyBeforeSSE(t *testing.T) {
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(context.Context, time.Duration) error {
			return nil
		},
	}
	body := strings.Replace(prototypeValidRunBody(t), `"protocol_version"`, `"PROTOCOL_VERSION"`, 1)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(body))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusBadRequest || strings.Contains(res.Header().Get("Content-Type"), "text/event-stream") || strings.Contains(res.Body.String(), "event:") {
		t.Fatalf("case-variant key status=%d type=%q body=%s", res.Code, res.Header().Get("Content-Type"), res.Body.String())
	}
}

func TestPrototypeRunRejectsCaseVariantDuplicateRequestKeyBeforeSSE(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	body := strings.TrimSuffix(prototypeValidRunBody(t), "}") + `,"RUN_ID":"run-duplicate"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(body))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusBadRequest || strings.Contains(res.Header().Get("Content-Type"), "text/event-stream") || strings.Contains(res.Body.String(), "event:") {
		t.Fatalf("case-variant duplicate status=%d type=%q body=%s", res.Code, res.Header().Get("Content-Type"), res.Body.String())
	}
}

func TestPrototypeRunRejectsEscapedRequestKeyBeforeSSE(t *testing.T) {
	a := &app{profiles: map[string]*Profile{}, dataDir: t.TempDir()}
	body := strings.Replace(prototypeValidRunBody(t), `"run_id"`, `"\u0072un_id"`, 1)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(body))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusBadRequest || strings.Contains(res.Header().Get("Content-Type"), "text/event-stream") || strings.Contains(res.Body.String(), "event:") {
		t.Fatalf("escaped key status=%d type=%q body=%s", res.Code, res.Header().Get("Content-Type"), res.Body.String())
	}
}

func TestPrototypeRunTerminalReceiptUsesProtocolVersionOnly(t *testing.T) {
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(context.Context, time.Duration) error {
			return nil
		},
	}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBody(t)))
	res := httptest.NewRecorder()
	a.routes().ServeHTTP(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", res.Code, res.Body.String())
	}
	frames := strings.Split(strings.TrimSuffix(res.Body.String(), "\n\n"), "\n\n")
	var envelope map[string]any
	if err := json.Unmarshal([]byte(strings.TrimPrefix(frames[len(frames)-1], "event: done\ndata: ")), &envelope); err != nil {
		t.Fatalf("terminal envelope: %v", err)
	}
	receipt, ok := envelope["details"].(map[string]any)
	if !ok {
		t.Fatalf("terminal details = %#v", envelope["details"])
	}
	if receipt["protocol_version"] != "prototype-stream-0.1" {
		t.Fatalf("wire protocol = %#v", receipt["protocol_version"])
	}
	for _, removed := range []string{"receipt_version", "canonical_receipt_version", "workload_id", "workload_version"} {
		if _, ok := receipt[removed]; ok {
			t.Fatalf("removed non-wire field %q still present", removed)
		}
	}
}

type noFlusherResponseWriter struct {
	header http.Header
	body   bytes.Buffer
	status int
}

type failingFlusherResponseWriter struct {
	header      http.Header
	body        bytes.Buffer
	status      int
	writes      int
	failOnWrite int
}

func (w *failingFlusherResponseWriter) Header() http.Header { return w.header }

func (w *failingFlusherResponseWriter) WriteHeader(status int) { w.status = status }

func (w *failingFlusherResponseWriter) Write(p []byte) (int, error) {
	w.writes++
	if w.writes == w.failOnWrite {
		return 0, errors.New("injected response writer failure")
	}
	if w.status == 0 {
		w.status = http.StatusOK
	}
	return w.body.Write(p)
}

func (w *failingFlusherResponseWriter) Flush() {}

func (w *noFlusherResponseWriter) Header() http.Header { return w.header }

func (w *noFlusherResponseWriter) WriteHeader(status int) { w.status = status }

func (w *noFlusherResponseWriter) Write(p []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	return w.body.Write(p)
}

func TestPrototypeRunPreStreamWriterRejectionDoesNotBurnRunID(t *testing.T) {
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(context.Context, time.Duration) error {
			return nil
		},
	}
	firstReq := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBody(t)))
	firstWriter := &noFlusherResponseWriter{header: make(http.Header)}
	a.routes().ServeHTTP(firstWriter, firstReq)
	if firstWriter.status != http.StatusInternalServerError {
		t.Fatalf("non-streaming writer status = %d, body = %s", firstWriter.status, firstWriter.body.String())
	}
	if len(a.prototypeRuns) != 0 {
		t.Fatalf("active run registry after pre-stream rejection = %d, want 0", len(a.prototypeRuns))
	}
	secondReq := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBody(t)))
	secondRes := httptest.NewRecorder()
	a.routes().ServeHTTP(secondRes, secondReq)
	if secondRes.Code != http.StatusOK || !strings.Contains(secondRes.Body.String(), "event: done") {
		t.Fatalf("same run id after pre-stream rejection status=%d body=%s", secondRes.Code, secondRes.Body.String())
	}
	if len(a.prototypeRuns) != 0 {
		t.Fatalf("active run registry after pre-stream rejection liveness = %d, want 0", len(a.prototypeRuns))
	}
}

func prototypeValidRunBodyWithIDs(t *testing.T, campaignID, runID string) string {
	t.Helper()
	body := prototypeValidRunBody(t)
	body = strings.Replace(body, "00000000-0000-0000-0000-000000000001", campaignID, 1)
	body = strings.Replace(body, "00000000-0000-0000-0000-000000000002", runID, 1)
	return body
}

func prototypeValidRunBodyForCondition(t *testing.T, campaignID, runID, conditionID string, runIndex int) string {
	return prototypeValidRunBodyForModeAndCondition(t, campaignID, runID, "quick", conditionID, runIndex)
}

func prototypeValidRunBodyForModeAndCondition(t *testing.T, campaignID, runID, campaignMode, conditionID string, runIndex int) string {
	t.Helper()
	body := prototypeValidRunBodyWithIDs(t, campaignID, runID)
	body = strings.Replace(body, `"campaign_mode":"quick"`, fmt.Sprintf(`"campaign_mode":"%s"`, campaignMode), 1)
	body = strings.Replace(body, `"run_index":1`, fmt.Sprintf(`"run_index":%d`, runIndex), 1)
	body = strings.Replace(body, `"condition_id":"baseline_v0.1"`, fmt.Sprintf(`"condition_id":"%s"`, conditionID), 1)
	return body
}

func TestPrototypeRunReleasesRunIDAfterResponseWriteFailure(t *testing.T) {
	for _, tc := range []struct {
		name        string
		failOnWrite int
	}{
		{name: "run_started", failOnWrite: 1},
		{name: "content_event", failOnWrite: 2},
		{name: "done", failOnWrite: 122},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a := &app{
				profiles: map[string]*Profile{},
				dataDir:  t.TempDir(),
				prototypeSleep: func(context.Context, time.Duration) error {
					return nil
				},
			}
			body := prototypeValidRunBodyWithIDs(t, "campaign-write-"+tc.name, "run-write-"+tc.name)
			req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(body))
			writer := &failingFlusherResponseWriter{header: make(http.Header), failOnWrite: tc.failOnWrite}
			a.routes().ServeHTTP(writer, req)
			if len(a.prototypeRuns) != 0 {
				t.Fatalf("active run registry after %s write failure = %d, want 0", tc.name, len(a.prototypeRuns))
			}
			freshBody := prototypeValidRunBodyWithIDs(t, "campaign-write-fresh-"+tc.name, "run-write-fresh-"+tc.name)
			freshReq := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(freshBody))
			freshRes := httptest.NewRecorder()
			a.routes().ServeHTTP(freshRes, freshReq)
			if freshRes.Code != http.StatusOK || !strings.Contains(freshRes.Body.String(), "event: done") {
				t.Fatalf("fresh run-id after %s write failure status=%d body=%s", tc.name, freshRes.Code, freshRes.Body.String())
			}
			if len(a.prototypeRuns) != 0 {
				t.Fatalf("active run registry after fresh %s liveness = %d, want 0", tc.name, len(a.prototypeRuns))
			}
		})
	}
}

func TestPrototypeRunReleasesRunIDAfterSuccess(t *testing.T) {
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(context.Context, time.Duration) error {
			return nil
		},
	}
	for attempt := 0; attempt < 2; attempt++ {
		body := prototypeValidRunBodyWithIDs(t, fmt.Sprintf("campaign-success-%d", attempt+1), fmt.Sprintf("run-success-%d", attempt+1))
		req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(body))
		res := httptest.NewRecorder()
		a.routes().ServeHTTP(res, req)
		if res.Code != http.StatusOK || !strings.Contains(res.Body.String(), "event: done") {
			t.Fatalf("success attempt %d status=%d body=%s", attempt+1, res.Code, res.Body.String())
		}
		if len(a.prototypeRuns) != 0 {
			t.Fatalf("active run registry after success attempt %d = %d, want 0", attempt+1, len(a.prototypeRuns))
		}
	}
}

func TestPrototypeRunReleasesRunIDAfterCancellation(t *testing.T) {
	started := make(chan struct{})
	var sleepCalls atomic.Int32
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(ctx context.Context, _ time.Duration) error {
			if sleepCalls.Add(1) == 1 {
				close(started)
				<-ctx.Done()
				return ctx.Err()
			}
			return nil
		},
	}
	ctx, cancel := context.WithCancel(context.Background())
	firstReq := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBody(t))).WithContext(ctx)
	firstRes := httptest.NewRecorder()
	firstDone := make(chan struct{})
	go func() {
		a.routes().ServeHTTP(firstRes, firstReq)
		close(firstDone)
	}()
	<-started
	cancel()
	<-firstDone
	if firstRes.Code != http.StatusOK || !strings.Contains(firstRes.Body.String(), "event: run_cancelled") {
		t.Fatalf("cancelled attempt status=%d body=%s", firstRes.Code, firstRes.Body.String())
	}
	if len(a.prototypeRuns) != 0 {
		t.Fatalf("active run registry after cancellation = %d, want 0", len(a.prototypeRuns))
	}
	secondReq := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBodyWithIDs(t, "campaign-cancel-2", "run-cancel-2")))
	secondRes := httptest.NewRecorder()
	a.routes().ServeHTTP(secondRes, secondReq)
	if secondRes.Code != http.StatusOK || !strings.Contains(secondRes.Body.String(), "event: done") {
		t.Fatalf("fresh run-id after cancellation status=%d body=%s", secondRes.Code, secondRes.Body.String())
	}
	if len(a.prototypeRuns) != 0 {
		t.Fatalf("active run registry after fresh cancellation liveness = %d, want 0", len(a.prototypeRuns))
	}
}

func TestPrototypeRunReleasesRunIDAfterFailure(t *testing.T) {
	var sleepCalls atomic.Int32
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(context.Context, time.Duration) error {
			if sleepCalls.Add(1) == 1 {
				return errors.New("injected terminal failure")
			}
			return nil
		},
	}
	firstReq := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBody(t)))
	firstRes := httptest.NewRecorder()
	a.routes().ServeHTTP(firstRes, firstReq)
	if firstRes.Code != http.StatusOK || !strings.Contains(firstRes.Body.String(), "event: run_failed") {
		t.Fatalf("failed attempt status=%d body=%s", firstRes.Code, firstRes.Body.String())
	}
	if len(a.prototypeRuns) != 0 {
		t.Fatalf("active run registry after failure = %d, want 0", len(a.prototypeRuns))
	}
	secondReq := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBodyWithIDs(t, "campaign-failure-2", "run-failure-2")))
	secondRes := httptest.NewRecorder()
	a.routes().ServeHTTP(secondRes, secondReq)
	if secondRes.Code != http.StatusOK || !strings.Contains(secondRes.Body.String(), "event: done") {
		t.Fatalf("fresh run-id after failure status=%d body=%s", secondRes.Code, secondRes.Body.String())
	}
	if len(a.prototypeRuns) != 0 {
		t.Fatalf("active run registry after fresh failure liveness = %d, want 0", len(a.prototypeRuns))
	}
}

func TestPrototypeRunReleasesRunIDOnPanic(t *testing.T) {
	var sleepCalls atomic.Int32
	a := &app{
		profiles: map[string]*Profile{},
		dataDir:  t.TempDir(),
		prototypeSleep: func(context.Context, time.Duration) error {
			if sleepCalls.Add(1) == 1 {
				panic("injected panic")
			}
			return nil
		},
	}
	var recovered any
	func() {
		defer func() { recovered = recover() }()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBody(t)))
		res := httptest.NewRecorder()
		a.routes().ServeHTTP(res, req)
	}()
	if recovered == nil {
		t.Fatal("expected injected handler panic")
	}
	if len(a.prototypeRuns) != 0 {
		t.Fatalf("active run registry after panic = %d, want 0", len(a.prototypeRuns))
	}
	freshReq := httptest.NewRequest(http.MethodPost, "/api/v1/prototype/runs", strings.NewReader(prototypeValidRunBodyWithIDs(t, "campaign-panic-2", "run-panic-2")))
	freshRes := httptest.NewRecorder()
	a.routes().ServeHTTP(freshRes, freshReq)
	if freshRes.Code != http.StatusOK || !strings.Contains(freshRes.Body.String(), "event: done") {
		t.Fatalf("fresh run-id after panic status=%d body=%s", freshRes.Code, freshRes.Body.String())
	}
	if len(a.prototypeRuns) != 0 {
		t.Fatalf("active run registry after fresh panic liveness = %d, want 0", len(a.prototypeRuns))
	}
}
