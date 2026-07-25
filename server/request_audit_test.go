package main

import (
	"bytes"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

const (
	testAuditInstanceID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	testRunID           = "019f731f-602a-72b3-abeb-85afa315e0f0"
)

func TestRequestAuditDoesNotDelayEchoResponseOrAlterSemantics(t *testing.T) {
	responseBody := []byte(`{"status":"ok","value":42}`)
	inner := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("X-Test-Semantics", "preserved")
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write(responseBody)
	})

	baselineServer := httptest.NewServer(inner)
	t.Cleanup(baselineServer.Close)
	baseline := getResponseSnapshot(t, baselineServer.URL+"/api/v1/echo")

	blockedWriter := newBlockingWriter()
	sink := newAsyncRequestAuditSink(log.New(blockedWriter, "", 0), 4)
	auditedServer := httptest.NewServer(withRequestAuditSink(inner, sink))
	t.Cleanup(auditedServer.Close)
	// Registered after server.Close so LIFO cleanup releases a deliberately
	// blocked sink first even when the non-blocking contract regresses.
	t.Cleanup(func() {
		blockedWriter.release()
		sink.Close()
	})

	result := make(chan responseSnapshot, 1)
	go func() {
		result <- getResponseSnapshot(t, auditedServer.URL+"/api/v1/echo")
	}()

	select {
	case <-blockedWriter.entered:
	case <-time.After(time.Second):
		t.Fatal("audit writer was not reached")
	}

	select {
	case got := <-result:
		if got.status != baseline.status || got.contentType != baseline.contentType ||
			got.semanticHeader != baseline.semanticHeader || !bytes.Equal(got.body, baseline.body) {
			t.Fatalf("audited response changed semantics: got=%+v baseline=%+v", got, baseline)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("echo response waited for blocked audit log I/O")
	}
}

type responseSnapshot struct {
	status         int
	contentType    string
	semanticHeader string
	body           []byte
}

func getResponseSnapshot(t *testing.T, url string) responseSnapshot {
	t.Helper()
	resp, err := http.Get(url)
	if err != nil {
		t.Errorf("GET %s: %v", url, err)
		return responseSnapshot{}
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Errorf("read %s: %v", url, err)
	}
	return responseSnapshot{
		status:         resp.StatusCode,
		contentType:    resp.Header.Get("Content-Type"),
		semanticHeader: resp.Header.Get("X-Test-Semantics"),
		body:           body,
	}
}

type blockingWriter struct {
	entered chan struct{}
	unblock chan struct{}
	once    sync.Once
}

func newBlockingWriter() *blockingWriter {
	return &blockingWriter{entered: make(chan struct{}), unblock: make(chan struct{})}
}

func (w *blockingWriter) Write(p []byte) (int, error) {
	w.once.Do(func() { close(w.entered) })
	<-w.unblock
	return len(p), nil
}

func (w *blockingWriter) release() {
	select {
	case <-w.unblock:
	default:
		close(w.unblock)
	}
}

func TestRequestAuditControlOnlyHasZeroBusinessRequests(t *testing.T) {
	logs := captureRequestAuditLogs(t, func(handler http.Handler) {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/serverinfo?secret=not-logged", nil)
		req.Header.Set(anebRunIDHeader, testRunID)
		req.Header.Set(anebAuditRoleHeader, "capability")
		resp := httptest.NewRecorder()
		handler.ServeHTTP(resp, req)
		if resp.Code != http.StatusOK {
			t.Fatalf("serverinfo status=%d", resp.Code)
		}
	})

	if got := strings.Count(logs, "class=control"); got != 1 {
		t.Fatalf("control audit count=%d logs=%q", got, logs)
	}
	if got := strings.Count(logs, "class=business"); got != 0 {
		t.Fatalf("business audit count=%d logs=%q", got, logs)
	}
	want := "ANEB_REQUEST_AUDIT instance_id=" + testAuditInstanceID +
		" seq=1 class=control method=GET path=/api/v1/serverinfo role=capability scope=token_run run_id=" + testRunID + "\n"
	if logs != want {
		t.Fatalf("audit log mismatch\n got: %q\nwant: %q", logs, want)
	}
}

func TestRequestAuditEnqueuesBusinessBeforeAConcurrentPostRunBarrier(t *testing.T) {
	enteredBusiness := make(chan struct{})
	releaseBusiness := make(chan struct{})
	sink := &recordingAuditSink{}
	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/v1/echo" {
			close(enteredBusiness)
			<-releaseBusiness
		}
		w.WriteHeader(http.StatusOK)
	})
	handler := withRequestAuditSink(inner, sink)

	businessDone := make(chan struct{})
	go func() {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/echo", strings.NewReader("{}"))
		req.Header.Set(anebRunIDHeader, "11111111-1111-4111-8111-111111111111")
		handler.ServeHTTP(httptest.NewRecorder(), req)
		close(businessDone)
	}()
	<-enteredBusiness

	barrier := httptest.NewRequest(http.MethodGet, "/api/v1/serverinfo", nil)
	barrier.Header.Set(anebRunIDHeader, "22222222-2222-4222-8222-222222222222")
	handler.ServeHTTP(httptest.NewRecorder(), barrier)
	close(releaseBusiness)
	<-businessDone

	records := sink.snapshot()
	if len(records) != 2 || records[0].runID != "11111111-1111-4111-8111-111111111111" ||
		records[1].runID != "22222222-2222-4222-8222-222222222222" {
		t.Fatalf("audit FIFO boundary invalid: %+v", records)
	}
}

func TestRequestAuditLoggerHasNoPrefix(t *testing.T) {
	var out bytes.Buffer
	logger := newRequestAuditLogger(&out)
	logger.Printf("ANEB_REQUEST_AUDIT class=control")
	if got, want := out.String(), "ANEB_REQUEST_AUDIT class=control\n"; got != want {
		t.Fatalf("audit logger grammar mismatch: got=%q want=%q", got, want)
	}
}

type recordingAuditSink struct {
	mu      sync.Mutex
	records []requestAuditRecord
}

func (s *recordingAuditSink) TryEmit(record requestAuditRecord) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.records = append(s.records, record)
	return true
}

func (s *recordingAuditSink) snapshot() []requestAuditRecord {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]requestAuditRecord(nil), s.records...)
}

func TestRequestAuditLogsAllTokenBusinessPrimitives(t *testing.T) {
	runID := testRunID
	logs := captureRequestAuditLogs(t, func(handler http.Handler) {
		echo := httptest.NewRequest(http.MethodPost, "/api/v1/echo", strings.NewReader("{}"))
		echo.Header.Set(anebRunIDHeader, runID)
		handler.ServeHTTP(httptest.NewRecorder(), echo)

		plan := validTokenSimPlan()
		token := httptest.NewRequest(
			http.MethodPost,
			"/api/v1/token-sim",
			bytes.NewReader(tokenSimRequestBody(t, plan, int(plan.UploadPayloadBytes))),
		)
		token.Header.Set(anebRunIDHeader, runID)
		handler.ServeHTTP(httptest.NewRecorder(), token)

		download := httptest.NewRequest(http.MethodGet, "/api/v1/download?bytes=1&chunk_kb=1", nil)
		download.Header.Set(anebRunIDHeader, runID)
		handler.ServeHTTP(httptest.NewRecorder(), download)
	})

	if got := strings.Count(logs, "class=business"); got != 3 {
		t.Fatalf("business audit count=%d logs=%q", got, logs)
	}
	for _, fragment := range []string{
		"method=POST path=/api/v1/echo role=none scope=token_run run_id=" + runID,
		"method=POST path=/api/v1/token-sim role=none scope=token_run run_id=" + runID,
		"method=GET path=/api/v1/download role=none scope=token_run run_id=" + runID,
	} {
		if !strings.Contains(logs, fragment) {
			t.Fatalf("missing %q in logs=%q", fragment, logs)
		}
	}
}

func TestRequestAuditRedactsLegacyInvalidAndDuplicateRunIDs(t *testing.T) {
	logs := captureRequestAuditLogs(t, func(handler http.Handler) {
		missing := httptest.NewRequest(http.MethodPost, "/api/v1/echo?query-secret=1", strings.NewReader("body-secret"))
		missing.RemoteAddr = "203.0.113.10:4567"
		handler.ServeHTTP(httptest.NewRecorder(), missing)

		invalid := httptest.NewRequest(http.MethodPost, "/api/v1/echo", strings.NewReader("{}"))
		invalid.Header[anebRunIDHeader] = []string{"unsafe value\nheader-secret"}
		handler.ServeHTTP(httptest.NewRecorder(), invalid)

		duplicate := httptest.NewRequest(http.MethodPost, "/api/v1/echo", strings.NewReader("{}"))
		duplicate.Header[anebRunIDHeader] = []string{
			"11111111-1111-4111-8111-111111111111",
			"22222222-2222-4222-8222-222222222222",
		}
		handler.ServeHTTP(httptest.NewRecorder(), duplicate)
	})

	if got := strings.Count(logs, "scope=legacy_unscoped run_id=none"); got != 1 {
		t.Fatalf("legacy marker count=%d logs=%q", got, logs)
	}
	if got := strings.Count(logs, "scope=invalid_header run_id=redacted"); got != 2 {
		t.Fatalf("invalid marker count=%d logs=%q", got, logs)
	}
	for _, secret := range []string{
		"query-secret", "body-secret", "203.0.113.10", "unsafe value", "header-secret",
		"11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
	} {
		if strings.Contains(logs, secret) {
			t.Fatalf("audit log leaked %q: %q", secret, logs)
		}
	}
}

func TestRequestAuditNormalizesUnexpectedMethodsWithoutPoisoningGrammar(t *testing.T) {
	logs := captureRequestAuditLogs(t, func(handler http.Handler) {
		req := httptest.NewRequest(http.MethodHead, "/api/v1/echo", nil)
		handler.ServeHTTP(httptest.NewRecorder(), req)
	})
	want := "ANEB_REQUEST_AUDIT instance_id=" + testAuditInstanceID +
		" seq=1 class=business method=OTHER path=/api/v1/echo role=none scope=legacy_unscoped run_id=none\n"
	if logs != want {
		t.Fatalf("unexpected method was not normalized: got=%q want=%q", logs, want)
	}
}

func TestRequestAuditDistinguishesLegacyUnscopedFromInvalidRunHeader(t *testing.T) {
	logs := captureRequestAuditLogs(t, func(handler http.Handler) {
		legacy := httptest.NewRequest(http.MethodPost, "/api/v1/echo", strings.NewReader("{}"))
		handler.ServeHTTP(httptest.NewRecorder(), legacy)

		invalid := httptest.NewRequest(http.MethodPost, "/api/v1/echo", strings.NewReader("{}"))
		invalid.Header.Set(anebRunIDHeader, "unsafe value")
		handler.ServeHTTP(httptest.NewRecorder(), invalid)

		for _, reserved := range []string{"none", "redacted"} {
			req := httptest.NewRequest(http.MethodPost, "/api/v1/echo", strings.NewReader("{}"))
			req.Header.Set(anebRunIDHeader, reserved)
			handler.ServeHTTP(httptest.NewRecorder(), req)
		}
	})

	if !strings.Contains(logs, "scope=legacy_unscoped run_id=none") {
		t.Fatalf("legacy request was not explicitly classified: %q", logs)
	}
	if got := strings.Count(logs, "scope=invalid_header run_id=redacted"); got != 3 {
		t.Fatalf("invalid header was not explicitly classified and redacted: %q", logs)
	}
	if strings.Contains(logs, "run_id=missing") || strings.Contains(logs, "unsafe value") {
		t.Fatalf("ambiguous or sensitive audit value leaked: %q", logs)
	}
}

func TestAuditIdentityRequiresCanonicalUUIDAndRejectsSecretLikeValues(t *testing.T) {
	if scope, runID := auditIdentity([]string{testRunID}); scope != "token_run" || runID != testRunID {
		t.Fatalf("canonical UUID rejected: scope=%q run_id=%q", scope, runID)
	}
	for _, unsafe := range []string{
		"gh" + "p_" + strings.Repeat("x", 36),
		"019F731F-602A-72B3-ABEB-85AFA315E0F0",
		"019f731f602a72b3abeb85afa315e0f0",
		"00000000-0000-0000-0000-000000000000",
		"019f731f-602a-72b3-7beb-85afa315e0f0",
	} {
		scope, runID := auditIdentity([]string{unsafe})
		if scope != "invalid_header" || runID != "redacted" {
			t.Fatalf("unsafe run ID was not redacted: value=%q scope=%q run_id=%q", unsafe, scope, runID)
		}
	}
}

func TestRequestAuditNormalizesAllContractAndUnknownAPIRoutes(t *testing.T) {
	for path, want := range requestAuditContractRoutes {
		got, ok := normalizeRequestAuditRoute(path)
		if !ok || got != want {
			t.Errorf("contract route %q: got=%+v ok=%v want=%+v", path, got, ok, want)
		}
	}

	for _, path := range []string{
		"/api/v1/profiles",
		"/api/v1/stream",
		"/api/v1/realtime-sim",
		"/api/v1/upload",
		"/api/v1/toolloop",
		"/api/v1/results",
		"/api/v1/impairments",
		"/api/v1/recovery",
		"/api/v1/private-secret",
		"/api/v1/echo/private-secret",
		"/api/v1/%0Aprivate-secret",
	} {
		got, ok := normalizeRequestAuditRoute(path)
		if !ok || got != requestAuditOtherRoute {
			t.Errorf("unknown API path %q was not normalized: got=%+v ok=%v", path, got, ok)
		}
	}

	got, ok := normalizeRequestAuditRoute("/synthetic/weak-capacity-latency-v1/api/v1/echo")
	if !ok || got != requestAuditOtherRoute {
		t.Fatalf("synthetic echo must not masquerade as direct echo: got=%+v ok=%v", got, ok)
	}
	got, ok = normalizeRequestAuditRoute("/synthetic/unknown/api/v1/serverinfo")
	if !ok || got != requestAuditOtherRoute {
		t.Fatalf("synthetic control-looking route must be other business: got=%+v ok=%v", got, ok)
	}
	got, ok = normalizeRequestAuditRoute("/synthetic/private-secret/header-secret")
	if !ok || got != requestAuditOtherRoute {
		t.Fatalf("unknown synthetic path was not normalized: got=%+v ok=%v", got, ok)
	}
	if _, ok := normalizeRequestAuditRoute("/health/private-secret"); ok {
		t.Fatal("non-API path was unexpectedly audited")
	}
}

func TestRequestAuditRoleIsFixedAndNeverLogsUnknownRawValue(t *testing.T) {
	for value, want := range map[string]requestAuditRole{
		"reachability": requestAuditRoleReachability,
		"capability":   requestAuditRoleCapability,
		"window_start": requestAuditRoleWindowStart,
		"window_end":   requestAuditRoleWindowEnd,
	} {
		if got := normalizeRequestAuditRole("control", []string{value}); got != want {
			t.Errorf("control role %q: got=%q want=%q", value, got.String(), want.String())
		}
	}
	if got := normalizeRequestAuditRole("control", nil); got != requestAuditRoleNone {
		t.Fatalf("missing control role=%q want=none", got.String())
	}
	if got := normalizeRequestAuditRole("control", []string{"secret-role"}); got != requestAuditRoleOther {
		t.Fatalf("unknown control role=%q want=other", got.String())
	}
	if got := normalizeRequestAuditRole("control", []string{"capability", "secret-role"}); got != requestAuditRoleOther {
		t.Fatalf("duplicate control role=%q want=other", got.String())
	}
	if got := normalizeRequestAuditRole("business", nil); got != requestAuditRoleNone {
		t.Fatalf("missing business role=%q want=none", got.String())
	}
	if got := normalizeRequestAuditRole("business", []string{"capability"}); got != requestAuditRoleOther {
		t.Fatalf("business role header=%q want=other", got.String())
	}
	if got := requestAuditRole(255).String(); got != "other" {
		t.Fatalf("invalid internal role rendered as %q want=other", got)
	}

	logs := captureRequestAuditLogs(t, func(handler http.Handler) {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/serverinfo?query-secret=1", nil)
		req.Header.Set(anebRunIDHeader, testRunID)
		req.Header.Set(anebAuditRoleHeader, "raw-secret-role")
		handler.ServeHTTP(httptest.NewRecorder(), req)
	})
	if !strings.Contains(logs, "path=/api/v1/serverinfo role=other scope=token_run") {
		t.Fatalf("unknown role was not normalized: %q", logs)
	}
	for _, secret := range []string{"raw-secret-role", "query-secret"} {
		if strings.Contains(logs, secret) {
			t.Fatalf("audit log leaked %q: %q", secret, logs)
		}
	}
}

func TestRequestAuditUnknownAPILeaksNoPathQueryOrHeader(t *testing.T) {
	logs := captureRequestAuditLogs(t, func(handler http.Handler) {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/private-secret?query-secret=1", nil)
		req.Header.Set(anebRunIDHeader, "ghp_header_secret")
		req.Header.Set(anebAuditRoleHeader, "role-header-secret")
		handler.ServeHTTP(httptest.NewRecorder(), req)
	})
	wantFragment := "class=business method=GET path=/api/v1/other role=other scope=invalid_header run_id=redacted"
	if !strings.Contains(logs, wantFragment) {
		t.Fatalf("unknown API request not safely normalized: %q", logs)
	}
	for _, secret := range []string{"private-secret", "query-secret", "ghp_header_secret", "role-header-secret"} {
		if strings.Contains(logs, secret) {
			t.Fatalf("audit log leaked %q: %q", secret, logs)
		}
	}
}

func TestRequestAuditSeesSyntheticEarlyReturnExactlyOnce(t *testing.T) {
	logs := captureRequestAuditLogs(t, func(handler http.Handler) {
		// Missing impairment query fields returns before the synthetic handler
		// delegates to the logical API route.
		req := httptest.NewRequest(
			http.MethodPost,
			"/synthetic/weak-capacity-latency-v1/api/v1/echo?query-secret=1",
			strings.NewReader("body-secret"),
		)
		req.Header.Set(anebRunIDHeader, testRunID)
		resp := httptest.NewRecorder()
		handler.ServeHTTP(resp, req)
		if resp.Code != http.StatusBadRequest {
			t.Fatalf("synthetic early-return status=%d want=%d", resp.Code, http.StatusBadRequest)
		}
	})
	if got := strings.Count(logs, "ANEB_REQUEST_AUDIT instance_id="); got != 1 {
		t.Fatalf("synthetic early return audit count=%d logs=%q", got, logs)
	}
	if !strings.Contains(logs, "class=business method=POST path=/api/v1/other role=none scope=token_run run_id="+testRunID) {
		t.Fatalf("synthetic early return missing isolated audit: %q", logs)
	}
	for _, secret := range []string{"weak-capacity-latency-v1", "query-secret", "body-secret"} {
		if strings.Contains(logs, secret) {
			t.Fatalf("synthetic audit leaked %q: %q", secret, logs)
		}
	}
}

func TestAsyncRequestAuditSinkRestartChangesIdentityAndResetsSequence(t *testing.T) {
	firstID := mustNewCanonicalAuditUUID()
	secondID := mustNewCanonicalAuditUUID()
	if firstID == secondID || !canonicalAuditUUID.MatchString(firstID) || !canonicalAuditUUID.MatchString(secondID) {
		t.Fatalf("invalid restart identities: first=%q second=%q", firstID, secondID)
	}
	record := requestAuditRecord{
		class: "business", method: http.MethodPost, path: "/api/v1/echo",
		role: requestAuditRoleNone, scope: "token_run", runID: testRunID,
	}
	capture := func(instanceID string, count int) string {
		var out bytes.Buffer
		sink := newAsyncRequestAuditSinkForInstance(log.New(&out, "", 0), count, instanceID)
		for i := 0; i < count; i++ {
			if !sink.TryEmit(record) {
				t.Fatalf("instance %s record %d dropped", instanceID, i)
			}
		}
		sink.Close()
		return out.String()
	}

	firstLogs := capture(firstID, 2)
	secondLogs := capture(secondID, 1)
	if !strings.Contains(firstLogs, "instance_id="+firstID+" seq=1 ") ||
		!strings.Contains(firstLogs, "instance_id="+firstID+" seq=2 ") {
		t.Fatalf("first instance sequence not contiguous: %q", firstLogs)
	}
	if !strings.Contains(secondLogs, "instance_id="+secondID+" seq=1 ") || strings.Contains(secondLogs, " seq=2 ") {
		t.Fatalf("restart did not reset sequence: %q", secondLogs)
	}
}

func TestAsyncRequestAuditSinkConcurrentWritesHaveStrictlyContiguousSequence(t *testing.T) {
	const count = 128
	var out bytes.Buffer
	sink := newAsyncRequestAuditSinkForInstance(log.New(&out, "", 0), count, testAuditInstanceID)
	record := requestAuditRecord{
		class: "business", method: http.MethodPost, path: "/api/v1/echo",
		role: requestAuditRoleNone, scope: "token_run", runID: testRunID,
	}
	var workers sync.WaitGroup
	workers.Add(count)
	for i := 0; i < count; i++ {
		go func() {
			defer workers.Done()
			if !sink.TryEmit(record) {
				t.Error("sized audit queue unexpectedly dropped a concurrent record")
			}
		}()
	}
	workers.Wait()
	sink.Close()

	matches := regexp.MustCompile(` seq=([1-9][0-9]*) `).FindAllStringSubmatch(out.String(), -1)
	if len(matches) != count {
		t.Fatalf("sequence count=%d want=%d logs=%q", len(matches), count, out.String())
	}
	for index, match := range matches {
		seq, err := strconv.Atoi(match[1])
		if err != nil || seq != index+1 {
			t.Fatalf("sequence[%d]=%q err=%v want=%d", index, match[1], err, index+1)
		}
	}
}

func TestAsyncRequestAuditSinkDropsWithoutBlockingAndClosesSafely(t *testing.T) {
	blockedWriter := newBlockingWriter()
	var out bytes.Buffer
	writer := io.MultiWriter(blockedWriter, &out)
	sink := newAsyncRequestAuditSink(log.New(writer, "", 0), 1)
	record := requestAuditRecord{
		class: "business", method: http.MethodPost, path: "/api/v1/echo",
		role: requestAuditRoleNone, scope: "token_run", runID: testRunID,
	}

	if !sink.TryEmit(record) {
		t.Fatal("first record unexpectedly dropped")
	}
	select {
	case <-blockedWriter.entered:
	case <-time.After(time.Second):
		t.Fatal("worker did not start the blocking write")
	}
	if !sink.TryEmit(record) {
		t.Fatal("buffered record unexpectedly dropped")
	}
	started := time.Now()
	if sink.TryEmit(record) {
		t.Fatal("overflow record unexpectedly accepted")
	}
	if elapsed := time.Since(started); elapsed > 100*time.Millisecond {
		t.Fatalf("overflow path blocked for %s", elapsed)
	}
	if got := sink.Dropped(); got != 1 {
		t.Fatalf("dropped=%d want=1", got)
	}

	blockedWriter.release()
	sink.Close()
	sink.Close()
	if !strings.Contains(out.String(), "ANEB_REQUEST_AUDIT_DROP instance_id="+processAuditInstanceID+" seq=2 count=1 total=1") {
		t.Fatalf("drop record missing from logs: %q", out.String())
	}
	if sink.TryEmit(record) {
		t.Fatal("closed sink accepted a record")
	}
	if got := sink.Dropped(); got != 2 {
		t.Fatalf("post-close dropped=%d want=2", got)
	}
}

func captureRequestAuditLogs(t *testing.T, action func(http.Handler)) string {
	t.Helper()
	var out bytes.Buffer
	sink := newAsyncRequestAuditSinkForInstance(log.New(&out, "", 0), 16, testAuditInstanceID)
	handler := (&app{requestAudit: sink}).routes()
	action(handler)
	sink.Close()
	return out.String()
}
