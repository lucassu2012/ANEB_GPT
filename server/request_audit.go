package main

import (
	"crypto/rand"
	"encoding/hex"
	"io"
	"log"
	"net/http"
	"os"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
)

const (
	anebRunIDHeader     = "X-Aneb-Run-Id"
	anebAuditRoleHeader = "X-Aneb-Audit-Role"

	defaultRequestAuditQueueCapacity = 1024
)

// canonicalAuditUUID accepts only the lower-case RFC 4122 string form used by
// ANEB run IDs. Requiring a real version and variant prevents arbitrary header
// values (including secret-like tokens) from being copied into audit output.
var canonicalAuditUUID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

type requestAuditRole uint8

const (
	requestAuditRoleNone requestAuditRole = iota
	requestAuditRoleReachability
	requestAuditRoleCapability
	requestAuditRoleWindowStart
	requestAuditRoleWindowEnd
	requestAuditRoleOther
)

// String is deliberately total: even an invalid internal enum value is
// normalized instead of being formatted as a raw or numeric value.
func (r requestAuditRole) String() string {
	switch r {
	case requestAuditRoleNone:
		return "none"
	case requestAuditRoleReachability:
		return "reachability"
	case requestAuditRoleCapability:
		return "capability"
	case requestAuditRoleWindowStart:
		return "window_start"
	case requestAuditRoleWindowEnd:
		return "window_end"
	case requestAuditRoleOther:
		return "other"
	default:
		return "other"
	}
}

type requestAuditRoute struct {
	class string
	path  string
}

var requestAuditContractRoutes = map[string]requestAuditRoute{
	"/api/v1/echo":       {class: "business", path: "/api/v1/echo"},
	"/api/v1/token-sim":  {class: "business", path: "/api/v1/token-sim"},
	"/api/v1/download":   {class: "business", path: "/api/v1/download"},
	"/api/v1/serverinfo": {class: "control", path: "/api/v1/serverinfo"},
}

var requestAuditOtherRoute = requestAuditRoute{class: "business", path: "/api/v1/other"}

type requestAuditRecord struct {
	class  string
	method string
	path   string
	role   requestAuditRole
	scope  string
	runID  string
}

type requestAuditEmitter interface {
	TryEmit(requestAuditRecord) bool
}

// asyncRequestAuditSink keeps logger I/O permanently off request goroutines.
// Enqueue is bounded and non-blocking; overload is visible through Dropped and
// a coalesced ANEB_REQUEST_AUDIT_DROP record written by the sole worker.
// That same worker allocates seq immediately before every physical log write,
// so concurrent request goroutines cannot reorder or duplicate sequence IDs.
type asyncRequestAuditSink struct {
	logger     *log.Logger
	records    chan requestAuditRecord
	instanceID string
	sequence   uint64 // worker-owned
	reported   uint64 // worker-owned
	mu         sync.RWMutex
	closed     bool
	closeOne   sync.Once
	worker     sync.WaitGroup
	dropped    atomic.Uint64
}

// processAuditInstanceID is generated once for the lifetime of this process.
// A restart necessarily creates a new identity and resets the worker sequence.
var processAuditInstanceID = mustNewCanonicalAuditUUID()

func newAsyncRequestAuditSink(logger *log.Logger, capacity int) *asyncRequestAuditSink {
	return newAsyncRequestAuditSinkForInstance(logger, capacity, processAuditInstanceID)
}

// newAsyncRequestAuditSinkForInstance is an internal test seam for simulating
// process restarts without making identity caller-controlled in production.
func newAsyncRequestAuditSinkForInstance(logger *log.Logger, capacity int, instanceID string) *asyncRequestAuditSink {
	if logger == nil {
		panic("request audit logger must not be nil")
	}
	if capacity < 1 {
		panic("request audit queue capacity must be positive")
	}
	if !canonicalAuditUUID.MatchString(instanceID) {
		panic("request audit instance ID must be a canonical UUID")
	}
	sink := &asyncRequestAuditSink{
		logger:     logger,
		records:    make(chan requestAuditRecord, capacity),
		instanceID: instanceID,
	}
	sink.worker.Add(1)
	go sink.run()
	return sink
}

func mustNewCanonicalAuditUUID() string {
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil {
		panic("generate request audit instance ID: " + err.Error())
	}
	raw[6] = (raw[6] & 0x0f) | 0x40 // UUID version 4
	raw[8] = (raw[8] & 0x3f) | 0x80 // RFC 4122 variant
	encoded := hex.EncodeToString(raw[:])
	return encoded[:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" + encoded[16:20] + "-" + encoded[20:]
}

func (s *asyncRequestAuditSink) TryEmit(record requestAuditRecord) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.closed {
		s.dropped.Add(1)
		return false
	}
	select {
	case s.records <- record:
		return true
	default:
		s.dropped.Add(1)
		return false
	}
}

func (s *asyncRequestAuditSink) Dropped() uint64 {
	return s.dropped.Load()
}

func (s *asyncRequestAuditSink) Close() {
	s.closeOne.Do(func() {
		s.mu.Lock()
		s.closed = true
		close(s.records)
		s.mu.Unlock()
		s.worker.Wait()
	})
}

func (s *asyncRequestAuditSink) run() {
	defer s.worker.Done()
	for record := range s.records {
		s.writePendingDropRecord()
		s.writeAuditRecord(record)
	}
	s.writePendingDropRecord()
}

func (s *asyncRequestAuditSink) writeAuditRecord(record requestAuditRecord) {
	seq := s.nextSequence()
	s.logger.Printf(
		"ANEB_REQUEST_AUDIT instance_id=%s seq=%d class=%s method=%s path=%s role=%s scope=%s run_id=%s",
		s.instanceID,
		seq,
		record.class,
		record.method,
		record.path,
		record.role.String(),
		record.scope,
		record.runID,
	)
}

func (s *asyncRequestAuditSink) writePendingDropRecord() {
	total := s.dropped.Load()
	if total <= s.reported {
		return
	}
	seq := s.nextSequence()
	s.logger.Printf(
		"ANEB_REQUEST_AUDIT_DROP instance_id=%s seq=%d count=%d total=%d",
		s.instanceID,
		seq,
		total-s.reported,
		total,
	)
	s.reported = total
}

func (s *asyncRequestAuditSink) nextSequence() uint64 {
	s.sequence++
	return s.sequence
}

var processRequestAuditSink struct {
	sync.Once
	sink *asyncRequestAuditSink
}

func defaultRequestAuditSink() *asyncRequestAuditSink {
	processRequestAuditSink.Do(func() {
		processRequestAuditSink.sink = newAsyncRequestAuditSink(newRequestAuditLogger(os.Stderr), defaultRequestAuditQueueCapacity)
	})
	return processRequestAuditSink.sink
}

// Audit records deliberately have no logger prefix. journalctl -o cat must
// preserve the exact line grammar consumed by the fail-closed verifier.
func newRequestAuditLogger(output io.Writer) *log.Logger {
	return log.New(output, "", 0)
}

// withRequestAudit emits one stable, privacy-bounded record for every API and
// synthetic request through the process-lifetime default asynchronous sink.
func withRequestAudit(next http.Handler) http.Handler {
	return withRequestAuditSink(next, defaultRequestAuditSink())
}

func withRequestAuditSink(next http.Handler, sink requestAuditEmitter) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		route, ok := normalizeRequestAuditRoute(r.URL.Path)
		if ok {
			scope, runID := auditIdentity(r.Header.Values(anebRunIDHeader))
			record := requestAuditRecord{
				class:  route.class,
				method: auditMethod(r.Method),
				path:   route.path,
				role:   normalizeRequestAuditRole(route.class, r.Header.Values(anebAuditRoleHeader)),
				scope:  scope,
				runID:  runID,
			}
			// Enqueue before the handler can expose response bytes. A client can
			// therefore issue a post-run barrier only after every earlier request
			// has already entered this FIFO (or incremented the visible drop count).
			_ = sink.TryEmit(record)
		}
		next.ServeHTTP(w, r)
	})
}

func auditMethod(method string) string {
	switch method {
	case http.MethodGet, http.MethodPost:
		return method
	default:
		return "OTHER"
	}
}

// normalizeRequestAuditRoute is a strict Token Quick allow-list. No
// caller-controlled path fragment is ever logged: its four direct evidence
// routes retain their public name and every other /api/v1/* or /synthetic/*
// request becomes /api/v1/other.
func normalizeRequestAuditRoute(path string) (requestAuditRoute, bool) {
	if strings.HasPrefix(path, "/api/v1/") {
		if route, ok := requestAuditContractRoutes[path]; ok {
			return route, true
		}
		return requestAuditOtherRoute, true
	}
	if !strings.HasPrefix(path, "/synthetic/") {
		return requestAuditRoute{}, false
	}

	// Synthetic routes are deliberately not attributed to direct Token Quick
	// primitives. Even a logical /echo or /download is shaped traffic and must
	// not masquerade as evidence for an unshaped direct run.
	return requestAuditOtherRoute, true
}

func normalizeRequestAuditRole(class string, values []string) requestAuditRole {
	if class != "control" {
		if len(values) == 0 {
			return requestAuditRoleNone
		}
		return requestAuditRoleOther
	}
	if len(values) == 0 {
		return requestAuditRoleNone
	}
	if len(values) != 1 {
		return requestAuditRoleOther
	}
	switch values[0] {
	case "reachability":
		return requestAuditRoleReachability
	case "capability":
		return requestAuditRoleCapability
	case "window_start":
		return requestAuditRoleWindowStart
	case "window_end":
		return requestAuditRoleWindowEnd
	default:
		return requestAuditRoleOther
	}
}

// auditIdentity deliberately classifies a missing header as a legitimate
// legacy/unscoped request. Only one canonical UUID receives token_run scope;
// malformed, duplicate, upper-case, or secret-like values are redacted.
func auditIdentity(values []string) (scope string, runID string) {
	if len(values) == 0 {
		return "legacy_unscoped", "none"
	}
	if len(values) != 1 || !canonicalAuditUUID.MatchString(values[0]) {
		return "invalid_header", "redacted"
	}
	return "token_run", values[0]
}
