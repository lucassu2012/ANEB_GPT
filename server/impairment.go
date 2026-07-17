package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"hash/fnv"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	syntheticImpairmentHeader = "X-Aneb-Synthetic-Impairment"
	syntheticParametersHeader = "X-Aneb-Impairment-Parameters"
	maxSyntheticRuns          = 4096
	syntheticRunTTL           = 15 * time.Minute
)

var syntheticRunIDPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,128}$`)

type syntheticImpairmentPolicy struct {
	ContractVersion string   `json:"contract_version"`
	ProfileID       string   `json:"profile_id"`
	Version         string   `json:"version"`
	RouteID         string   `json:"route_id"`
	DownlinkMbps    float64  `json:"downlink_mbps"`
	UplinkMbps      float64  `json:"uplink_mbps"`
	AddedRTTMs      int      `json:"added_rtt_ms"`
	JitterMs        int      `json:"jitter_ms"`
	AppliesTo       []string `json:"applies_to"`
	Excluded        []string `json:"excluded_from_shaping"`
}

var weakCapacityLatencyPolicy = syntheticImpairmentPolicy{
	ContractVersion: "aneb-synthetic-impairment-v1",
	ProfileID:       "network_comprehensive_weak_capacity_latency",
	Version:         "1.0.0",
	RouteID:         "weak-capacity-latency-v1",
	DownlinkMbps:    3,
	UplinkMbps:      1,
	AddedRTTMs:      120,
	JitterMs:        30,
	AppliesTo:       []string{"http_request_delay", "http_request_body", "http_response_body"},
	Excluded:        []string{"dns", "tcp", "tls", "udp", "radio_rsrp", "radio_sinr"},
}

func syntheticPolicyByRoute(routeID string) (syntheticImpairmentPolicy, bool) {
	if routeID == weakCapacityLatencyPolicy.RouteID {
		return weakCapacityLatencyPolicy, true
	}
	return syntheticImpairmentPolicy{}, false
}

func (a *app) handleSyntheticImpairments(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(struct {
		Policies []syntheticImpairmentPolicy `json:"policies"`
	}{Policies: []syntheticImpairmentPolicy{weakCapacityLatencyPolicy}})
}

type syntheticImpairmentRegistry struct {
	mu   sync.Mutex
	runs map[string]*syntheticRunState
}

type syntheticRunState struct {
	uplink   serialByteLimiter
	downlink serialByteLimiter
	lastSeen time.Time
}

func (r *syntheticImpairmentRegistry) get(policy syntheticImpairmentPolicy, runID string) (*syntheticRunState, error) {
	now := time.Now()
	key := policy.ProfileID + "@" + policy.Version + ":" + runID
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.runs == nil {
		r.runs = make(map[string]*syntheticRunState)
	}
	for candidate, state := range r.runs {
		if now.Sub(state.lastSeen) > syntheticRunTTL {
			delete(r.runs, candidate)
		}
	}
	if state := r.runs[key]; state != nil {
		state.lastSeen = now
		return state, nil
	}
	if len(r.runs) >= maxSyntheticRuns {
		return nil, errors.New("too many active synthetic runs")
	}
	state := &syntheticRunState{
		uplink:   serialByteLimiter{bitsPerSecond: policy.UplinkMbps * 1_000_000},
		downlink: serialByteLimiter{bitsPerSecond: policy.DownlinkMbps * 1_000_000},
		lastSeen: now,
	}
	r.runs[key] = state
	return state, nil
}

// serialByteLimiter reserves completion slots under a mutex. All requests for
// one run share it, so parallel transfers cannot multiply the declared cap.
type serialByteLimiter struct {
	mu            sync.Mutex
	bitsPerSecond float64
	next          time.Time
}

func (l *serialByteLimiter) wait(ctx context.Context, byteCount int) error {
	if byteCount <= 0 || l.bitsPerSecond <= 0 {
		return nil
	}
	now := time.Now()
	duration := time.Duration(float64(byteCount*8) / l.bitsPerSecond * float64(time.Second))
	l.mu.Lock()
	start := now
	if l.next.After(start) {
		start = l.next
	}
	deadline := start.Add(duration)
	l.next = deadline
	l.mu.Unlock()

	if wait := time.Until(deadline); wait > 0 {
		timer := time.NewTimer(wait)
		defer timer.Stop()
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-timer.C:
		}
	}
	return nil
}

func (a *app) syntheticImpairmentHandler(api http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		trimmed := strings.TrimPrefix(r.URL.Path, "/synthetic/")
		parts := strings.SplitN(trimmed, "/", 2)
		if len(parts) != 2 {
			http.NotFound(w, r)
			return
		}
		policy, ok := syntheticPolicyByRoute(parts[0])
		if !ok {
			http.Error(w, "unknown synthetic impairment", http.StatusNotFound)
			return
		}
		rewrittenPath := "/" + parts[1]
		if rewrittenPath != "/api/v1/echo" && rewrittenPath != "/api/v1/download" && rewrittenPath != "/api/v1/upload" {
			http.Error(w, "endpoint not supported by synthetic impairment", http.StatusNotFound)
			return
		}

		q := r.URL.Query()
		runID := q.Get("impair_run")
		if !syntheticRunIDPattern.MatchString(runID) {
			http.Error(w, "invalid impair_run", http.StatusBadRequest)
			return
		}
		seed, err := strconv.ParseInt(q.Get("impair_seed"), 10, 64)
		if err != nil {
			http.Error(w, "invalid impair_seed", http.StatusBadRequest)
			return
		}
		seq, err := strconv.ParseUint(q.Get("impair_seq"), 10, 64)
		if err != nil {
			http.Error(w, "invalid impair_seq", http.StatusBadRequest)
			return
		}
		state, err := a.impairments.get(policy, runID)
		if err != nil {
			http.Error(w, err.Error(), http.StatusServiceUnavailable)
			return
		}
		if err := waitSyntheticDelay(r.Context(), policy, runID, seed, seq); err != nil {
			return
		}

		r.URL.Path = rewrittenPath
		r.Body = &limitedReadCloser{ReadCloser: r.Body, limiter: &state.uplink, ctx: r.Context()}
		w.Header().Set(syntheticImpairmentHeader, policy.ProfileID+"@"+policy.Version)
		w.Header().Set(syntheticParametersHeader, fmt.Sprintf("dl=%.3g;ul=%.3g;rtt=%d;jitter=%d", policy.DownlinkMbps, policy.UplinkMbps, policy.AddedRTTMs, policy.JitterMs))
		api.ServeHTTP(&limitedResponseWriter{ResponseWriter: w, limiter: &state.downlink, ctx: r.Context()}, r)
	})
}

func waitSyntheticDelay(ctx context.Context, policy syntheticImpairmentPolicy, runID string, seed int64, seq uint64) error {
	h := fnv.New64a()
	_, _ = fmt.Fprintf(h, "%s:%d:%d", runID, seed, seq)
	jitter := 0
	if policy.JitterMs > 0 {
		jitter = int(h.Sum64()%uint64(policy.JitterMs*2+1)) - policy.JitterMs
	}
	delay := time.Duration(policy.AddedRTTMs+jitter) * time.Millisecond
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

type limitedReadCloser struct {
	io.ReadCloser
	limiter *serialByteLimiter
	ctx     context.Context
}

func (r *limitedReadCloser) Read(p []byte) (int, error) {
	if len(p) > 16<<10 {
		p = p[:16<<10]
	}
	n, err := r.ReadCloser.Read(p)
	if waitErr := r.limiter.wait(r.ctx, n); waitErr != nil {
		return n, waitErr
	}
	return n, err
}

type limitedResponseWriter struct {
	http.ResponseWriter
	limiter *serialByteLimiter
	ctx     context.Context
}

func (w *limitedResponseWriter) Write(p []byte) (int, error) {
	total := 0
	for len(p) > 0 {
		n := len(p)
		if n > 16<<10 {
			n = 16 << 10
		}
		if err := w.limiter.wait(w.ctx, n); err != nil {
			return total, err
		}
		written, err := w.ResponseWriter.Write(p[:n])
		total += written
		if err != nil {
			return total, err
		}
		if written != n {
			return total, io.ErrShortWrite
		}
		p = p[n:]
	}
	return total, nil
}

func (w *limitedResponseWriter) Flush() {
	if flusher, ok := w.ResponseWriter.(http.Flusher); ok {
		flusher.Flush()
	}
}

func (w *limitedResponseWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }
