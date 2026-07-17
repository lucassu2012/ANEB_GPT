package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

func syntheticURL(base, endpoint, run string, seq int) string {
	return syntheticRouteURL(base, weakCapacityLatencyPolicy.RouteID, endpoint, run, seq)
}

func syntheticRouteURL(base, route, endpoint, run string, seq int) string {
	separator := "?"
	if strings.Contains(endpoint, "?") {
		separator = "&"
	}
	return base + "/synthetic/" + route + endpoint + separator +
		"impair_run=" + run + "&impair_seed=20260717&impair_seq=" + strconv.Itoa(seq)
}

func TestSyntheticImpairmentsCatalog(t *testing.T) {
	srv := httptest.NewServer((&app{}).routes())
	defer srv.Close()
	resp, err := http.Get(srv.URL + "/api/v1/impairments")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var catalog struct {
		Policies []syntheticImpairmentPolicy `json:"policies"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&catalog); err != nil {
		t.Fatal(err)
	}
	if len(catalog.Policies) != 2 || catalog.Policies[0].ContractVersion != "aneb-synthetic-impairment-v1" ||
		catalog.Policies[1].RouteID != weakRecoveryPolicy.RouteID || catalog.Policies[1].OutageDurationMs != 2_000 {
		t.Fatalf("unexpected catalog: %+v", catalog)
	}
}

func TestSyntheticRecoveryOutageIsRunScopedAndNormalPathIsUnaffected(t *testing.T) {
	srv := httptest.NewServer((&app{}).routes())
	defer srv.Close()
	url := func(endpoint, run string, seq int) string {
		return syntheticRouteURL(srv.URL, weakRecoveryPolicy.RouteID, endpoint, run, seq)
	}

	trigger, err := http.Post(url("/api/v1/recovery", "recovery-a", 1), "application/json", strings.NewReader("{}"))
	if err != nil {
		t.Fatal(err)
	}
	defer trigger.Body.Close()
	if trigger.StatusCode != http.StatusAccepted {
		t.Fatalf("trigger status=%d", trigger.StatusCode)
	}
	if got := trigger.Header.Get(syntheticImpairmentHeader); got != "network_comprehensive_weak_recovery@1.0.0" {
		t.Fatalf("trigger ack=%q", got)
	}
	if got := trigger.Header.Get(syntheticOutageDurationHeader); got != "2000" {
		t.Fatalf("outage duration=%q", got)
	}

	blocked, err := http.Post(url("/api/v1/echo", "recovery-a", 2), "application/json", strings.NewReader("{}"))
	if err != nil {
		t.Fatal(err)
	}
	blocked.Body.Close()
	if blocked.StatusCode != http.StatusServiceUnavailable || blocked.Header.Get(syntheticOutageHeader) != "active" {
		t.Fatalf("same-run outage status=%d header=%q", blocked.StatusCode, blocked.Header.Get(syntheticOutageHeader))
	}

	other, err := http.Post(url("/api/v1/echo", "recovery-b", 1), "application/json", strings.NewReader("{}"))
	if err != nil {
		t.Fatal(err)
	}
	other.Body.Close()
	if other.StatusCode != http.StatusOK {
		t.Fatalf("different run was affected: %d", other.StatusCode)
	}

	normal, err := http.Post(srv.URL+"/api/v1/echo", "application/json", strings.NewReader("{}"))
	if err != nil {
		t.Fatal(err)
	}
	normal.Body.Close()
	if normal.StatusCode != http.StatusOK || normal.Header.Get(syntheticImpairmentHeader) != "" {
		t.Fatalf("normal path was affected: status=%d ack=%q", normal.StatusCode, normal.Header.Get(syntheticImpairmentHeader))
	}

	time.Sleep(time.Duration(weakRecoveryPolicy.OutageDurationMs)*time.Millisecond + 100*time.Millisecond)
	recovered, err := http.Post(url("/api/v1/echo", "recovery-a", 3), "application/json", strings.NewReader("{}"))
	if err != nil {
		t.Fatal(err)
	}
	recovered.Body.Close()
	if recovered.StatusCode != http.StatusOK || recovered.Header.Get(syntheticOutageHeader) != "" {
		t.Fatalf("same run did not recover: status=%d header=%q", recovered.StatusCode, recovered.Header.Get(syntheticOutageHeader))
	}
}

func TestSyntheticRecoveryTriggerIsIdempotent(t *testing.T) {
	var state syntheticRunState
	first, until := state.triggerOutage(time.Now(), 2*time.Second)
	second, sameUntil := state.triggerOutage(time.Now().Add(time.Second), 2*time.Second)
	if !first || second || !sameUntil.Equal(until) {
		t.Fatalf("first=%v second=%v until=%v same=%v", first, second, until, sameUntil)
	}
}

func TestSyntheticRegistrySharesWithinRunAndIsolatesDifferentRuns(t *testing.T) {
	var registry syntheticImpairmentRegistry
	first, err := registry.get(weakCapacityLatencyPolicy, "run-a")
	if err != nil {
		t.Fatal(err)
	}
	same, err := registry.get(weakCapacityLatencyPolicy, "run-a")
	if err != nil {
		t.Fatal(err)
	}
	other, err := registry.get(weakCapacityLatencyPolicy, "run-b")
	if err != nil {
		t.Fatal(err)
	}
	if first != same {
		t.Fatal("parallel requests in one run must share aggregate limiters")
	}
	if first == other {
		t.Fatal("different runs must not share limiter state")
	}
}

func TestSyntheticServerPolicyMatchesPublishedProfile(t *testing.T) {
	for _, actual := range []syntheticImpairmentPolicy{weakCapacityLatencyPolicy, weakRecoveryPolicy} {
		raw, err := os.ReadFile("../profiles/published/" + actual.ProfileID + "/profile.json")
		if err != nil {
			t.Fatal(err)
		}
		var profile struct {
			ProfileID           string                    `json:"profile_id"`
			Version             string                    `json:"version"`
			SyntheticImpairment syntheticImpairmentPolicy `json:"synthetic_impairment"`
		}
		if err := json.Unmarshal(raw, &profile); err != nil {
			t.Fatal(err)
		}
		declared := profile.SyntheticImpairment
		if profile.ProfileID != actual.ProfileID || profile.Version != actual.Version ||
			declared.ContractVersion != actual.ContractVersion || declared.RouteID != actual.RouteID ||
			declared.DownlinkMbps != actual.DownlinkMbps || declared.UplinkMbps != actual.UplinkMbps ||
			declared.AddedRTTMs != actual.AddedRTTMs || declared.JitterMs != actual.JitterMs ||
			declared.OutageDurationMs != actual.OutageDurationMs {
			t.Fatalf("published profile and server policy drifted: profile=%+v policy=%+v", profile, actual)
		}
	}
}

func TestSyntheticEchoRequiresContractAndAcknowledgesPolicy(t *testing.T) {
	srv := httptest.NewServer((&app{}).routes())
	defer srv.Close()
	bad, err := http.Post(srv.URL+"/synthetic/weak-capacity-latency-v1/api/v1/echo", "application/json", strings.NewReader("{}"))
	if err != nil {
		t.Fatal(err)
	}
	bad.Body.Close()
	if bad.StatusCode != http.StatusBadRequest {
		t.Fatalf("bad request status=%d", bad.StatusCode)
	}

	started := time.Now()
	resp, err := http.Post(syntheticURL(srv.URL, "/api/v1/echo", "run-echo", 1), "application/json", strings.NewReader("{}"))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if elapsed := time.Since(started); elapsed < 85*time.Millisecond {
		t.Fatalf("synthetic echo returned without declared delay: %v", elapsed)
	}
	if got := resp.Header.Get(syntheticImpairmentHeader); got != "network_comprehensive_weak_capacity_latency@1.0.0" {
		t.Fatalf("ack=%q", got)
	}
	if got := resp.Header.Get(syntheticParametersHeader); got != "dl=3;ul=1;rtt=120;jitter=30" {
		t.Fatalf("parameters=%q", got)
	}
}

func TestSyntheticDownloadCapsAggregateRunAndNormalPathIsUnaffected(t *testing.T) {
	srv := httptest.NewServer((&app{}).routes())
	defer srv.Close()
	const bytesPerRequest = 96 << 10
	started := time.Now()
	done := make(chan error, 2)
	for i := 0; i < 2; i++ {
		go func(seq int) {
			resp, err := http.Get(syntheticURL(srv.URL, "/api/v1/download?bytes=98304&chunk_kb=16", "run-parallel", seq))
			if err != nil {
				done <- err
				return
			}
			defer resp.Body.Close()
			body, err := io.ReadAll(resp.Body)
			if err == nil && len(body) != bytesPerRequest {
				err = fmt.Errorf("bytes=%d", len(body))
			}
			done <- err
		}(i)
	}
	for i := 0; i < 2; i++ {
		if err := <-done; err != nil {
			t.Fatal(err)
		}
	}
	if elapsed := time.Since(started); elapsed < 430*time.Millisecond {
		t.Fatalf("parallel download bypassed aggregate cap: %v", elapsed)
	}

	normalStarted := time.Now()
	resp, err := http.Get(srv.URL + "/api/v1/download?bytes=98304&chunk_kb=16")
	if err != nil {
		t.Fatal(err)
	}
	_, err = io.Copy(io.Discard, resp.Body)
	resp.Body.Close()
	if err != nil {
		t.Fatal(err)
	}
	if elapsed := time.Since(normalStarted); elapsed > 400*time.Millisecond {
		t.Fatalf("normal path appears shaped: %v", elapsed)
	}
}

func TestSyntheticUploadCapsBodyAndPreservesBytes(t *testing.T) {
	srv := httptest.NewServer((&app{}).routes())
	defer srv.Close()
	body := bytes.Repeat([]byte{0x5a}, 64<<10)
	started := time.Now()
	resp, err := http.Post(syntheticURL(srv.URL, "/api/v1/upload", "run-upload", 1), "application/octet-stream", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var result uploadResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		t.Fatal(err)
	}
	if result.Bytes != int64(len(body)) {
		t.Fatalf("bytes=%d want=%d", result.Bytes, len(body))
	}
	if elapsed := time.Since(started); elapsed < 540*time.Millisecond {
		t.Fatalf("upload returned without declared delay+cap: %v", elapsed)
	}
}
