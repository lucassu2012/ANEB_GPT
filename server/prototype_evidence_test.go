package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

type gatedEOFBody struct {
	entered     chan struct{}
	released    chan struct{}
	enterOnce   sync.Once
	releaseOnce sync.Once
}

func newGatedEOFBody() *gatedEOFBody {
	return &gatedEOFBody{
		entered:  make(chan struct{}),
		released: make(chan struct{}),
	}
}

func (body *gatedEOFBody) Read([]byte) (int, error) {
	body.enterOnce.Do(func() { close(body.entered) })
	<-body.released
	return 0, io.EOF
}

func (body *gatedEOFBody) Close() error {
	body.releaseOnce.Do(func() { close(body.released) })
	return nil
}

func TestPrototypeEvidencePublicationIsUnavailableWithoutRuntime(t *testing.T) {
	a := &app{}
	req := httptest.NewRequest(
		http.MethodPost,
		"/api/v1/prototype/campaigns/evidence",
		strings.NewReader(`{"schema_version":"aneb-prototype-upload-0.1","campaign_id":"campaign-0001"}`),
	)
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()

	a.routes().ServeHTTP(recorder, req)

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("evidence status = %d, want %d; body=%s", recorder.Code, http.StatusServiceUnavailable, recorder.Body.String())
	}
	if got := recorder.Header().Get("Content-Type"); got != "application/json" {
		t.Fatalf("evidence content type = %q, want application/json", got)
	}
	if got := recorder.Body.String(); got != `{"code":"P018_EVIDENCE_PUBLICATION_FAILED","ok":false}`+"\n" {
		t.Fatalf("evidence failure body = %q", got)
	}
}

func TestPrototypeEvidencePublicationRejectsInvalidRequestBeforeRuntime(t *testing.T) {
	a := &app{
		prototypeEvidenceRuntime: filepath.Join(t.TempDir(), "must-not-run"),
		prototypeResultsRoot:     filepath.Join(t.TempDir(), "published"),
	}
	for _, testCase := range []struct {
		name        string
		method      string
		contentType string
		body        string
		wantStatus  int
	}{
		{name: "method", method: http.MethodGet, contentType: "application/json", body: `{}`, wantStatus: http.StatusMethodNotAllowed},
		{name: "content-type", method: http.MethodPost, contentType: "text/plain", body: `{}`, wantStatus: http.StatusUnsupportedMediaType},
		{name: "invalid-json", method: http.MethodPost, contentType: "application/json", body: `{`, wantStatus: http.StatusBadRequest},
		{name: "unsafe-campaign", method: http.MethodPost, contentType: "application/json", body: `{"campaign_id":"../escape"}`, wantStatus: http.StatusBadRequest},
		{name: "empty-body", method: http.MethodPost, contentType: "application/json", body: ``, wantStatus: http.StatusBadRequest},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			req := httptest.NewRequest(testCase.method, "/api/v1/prototype/campaigns/evidence", strings.NewReader(testCase.body))
			if testCase.contentType != "" {
				req.Header.Set("Content-Type", testCase.contentType)
			}
			recorder := httptest.NewRecorder()

			a.routes().ServeHTTP(recorder, req)

			if recorder.Code != testCase.wantStatus {
				t.Fatalf("invalid request status = %d, want %d; body=%s", recorder.Code, testCase.wantStatus, recorder.Body.String())
			}
			if got := recorder.Body.String(); got != `{"code":"P018_EVIDENCE_PUBLICATION_FAILED","ok":false}`+"\n" {
				t.Fatalf("invalid request failure body = %q", got)
			}
		})
	}
}

func TestPrototypeEvidencePublicationInvokesRuntimeAndReturnsBoundReceipt(t *testing.T) {
	runtimePath := buildPrototypeEvidenceRuntime(t)
	resultsRoot := filepath.Join(t.TempDir(), "published")
	a := &app{
		prototypeEvidenceRuntime: runtimePath,
		prototypeResultsRoot:     resultsRoot,
	}
	upload := `{"schema_version":"aneb-prototype-upload-0.1","campaign_id":"campaign-0001","meta_json":"{}\\n","events_jsonl":"{}\\n","runs_csv":"header\\n","summary_csv":"header\\n"}`
	req := httptest.NewRequest(
		http.MethodPost,
		"/api/v1/prototype/campaigns/evidence",
		strings.NewReader(upload),
	)
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()

	a.routes().ServeHTTP(recorder, req)

	if recorder.Code != http.StatusOK {
		t.Fatalf("evidence status = %d, want %d; body=%s", recorder.Code, http.StatusOK, recorder.Body.String())
	}
	if got := recorder.Header().Get("Content-Type"); got != "application/json" {
		t.Fatalf("evidence content type = %q, want application/json", got)
	}
	manifestPath := filepath.Join(resultsRoot, "campaign-0001", "manifest.json")
	manifestBytes, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatalf("read published manifest: %v", err)
	}
	manifestSum := sha256.Sum256(manifestBytes)
	wantReceipt := `{"campaign_id":"campaign-0001","manifest_sha256":"` + hex.EncodeToString(manifestSum[:]) + `","publication_status":"verified","schema_version":"aneb-prototype-publication-receipt-0.1"}` + "\n"
	if got := recorder.Body.String(); got != wantReceipt {
		t.Fatalf("evidence success body = %q, want %q", got, wantReceipt)
	}

	invocationBytes, err := os.ReadFile(filepath.Join(resultsRoot, "runtime-invocation.json"))
	if err != nil {
		t.Fatalf("read runtime invocation: %v", err)
	}
	var invocation struct {
		InputPath string `json:"input_path"`
		Body      string `json:"body"`
	}
	if err := json.Unmarshal(invocationBytes, &invocation); err != nil {
		t.Fatalf("decode runtime invocation: %v", err)
	}
	if invocation.Body != upload {
		t.Fatalf("runtime input body changed: %q", invocation.Body)
	}
	if _, err := os.Lstat(invocation.InputPath); !os.IsNotExist(err) {
		t.Fatalf("temporary upload still exists after response: %q err=%v", invocation.InputPath, err)
	}
}

func TestPrototypeEvidencePublicationNeverOverwritesPublishedCampaign(t *testing.T) {
	runtimePath := buildPrototypeEvidenceRuntime(t)
	resultsRoot := filepath.Join(t.TempDir(), "published")
	campaignRoot := filepath.Join(resultsRoot, "campaign-0001")
	if err := os.MkdirAll(campaignRoot, 0o755); err != nil {
		t.Fatalf("create published campaign: %v", err)
	}
	manifestPath := filepath.Join(campaignRoot, "manifest.json")
	originalManifest := []byte("immutable-manifest\n")
	if err := os.WriteFile(manifestPath, originalManifest, 0o644); err != nil {
		t.Fatalf("write published manifest: %v", err)
	}
	a := &app{
		prototypeEvidenceRuntime: runtimePath,
		prototypeResultsRoot:     resultsRoot,
	}
	req := httptest.NewRequest(
		http.MethodPost,
		"/api/v1/prototype/campaigns/evidence",
		strings.NewReader(`{"schema_version":"aneb-prototype-upload-0.1","campaign_id":"campaign-0001"}`),
	)
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()

	a.routes().ServeHTTP(recorder, req)

	if recorder.Code != http.StatusInternalServerError {
		t.Fatalf("duplicate evidence status = %d, want %d; body=%s", recorder.Code, http.StatusInternalServerError, recorder.Body.String())
	}
	if got := recorder.Body.String(); got != `{"code":"P018_EVIDENCE_PUBLICATION_FAILED","ok":false}`+"\n" {
		t.Fatalf("duplicate evidence failure body = %q", got)
	}
	manifestBytes, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatalf("read published manifest after duplicate: %v", err)
	}
	if string(manifestBytes) != string(originalManifest) {
		t.Fatalf("published manifest was overwritten: %q", manifestBytes)
	}
}

func TestPrototypeEvidencePublicationAllowsOnlyOneRuntimeAtATime(t *testing.T) {
	runtimePath := buildPrototypeEvidenceRuntime(t)
	resultsRoot := filepath.Join(t.TempDir(), "published")
	a := &app{
		prototypeEvidenceRuntime: runtimePath,
		prototypeResultsRoot:     resultsRoot,
	}

	var firstRecorder *httptest.ResponseRecorder
	var firstWG sync.WaitGroup
	firstWG.Add(1)
	go func() {
		defer firstWG.Done()
		firstReq := httptest.NewRequest(
			http.MethodPost,
			"/api/v1/prototype/campaigns/evidence",
			strings.NewReader(`{"schema_version":"aneb-prototype-upload-0.1","campaign_id":"campaign-block"}`),
		)
		firstReq.Header.Set("Content-Type", "application/json")
		firstRecorder = httptest.NewRecorder()
		a.routes().ServeHTTP(firstRecorder, firstReq)
	}()

	startedPath := filepath.Join(resultsRoot, "runtime-started")
	deadline := time.Now().Add(10 * time.Second)
	for {
		if _, err := os.Stat(startedPath); err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("first evidence runtime did not enter blocking state")
		}
		time.Sleep(10 * time.Millisecond)
	}

	secondReq := httptest.NewRequest(
		http.MethodPost,
		"/api/v1/prototype/campaigns/evidence",
		strings.NewReader(`{"schema_version":"aneb-prototype-upload-0.1","campaign_id":"campaign-0002"}`),
	)
	secondReq.Header.Set("Content-Type", "application/json")
	secondRecorder := httptest.NewRecorder()
	a.routes().ServeHTTP(secondRecorder, secondReq)
	if secondRecorder.Code != http.StatusTooManyRequests {
		t.Fatalf("concurrent evidence status = %d, want %d; body=%s", secondRecorder.Code, http.StatusTooManyRequests, secondRecorder.Body.String())
	}
	if got := secondRecorder.Body.String(); got != `{"code":"P018_EVIDENCE_PUBLICATION_FAILED","ok":false}`+"\n" {
		t.Fatalf("concurrent evidence failure body = %q", got)
	}

	if err := os.WriteFile(filepath.Join(resultsRoot, "runtime-release"), []byte("release\n"), 0o600); err != nil {
		t.Fatalf("release first runtime: %v", err)
	}
	firstWG.Wait()
	if firstRecorder == nil || firstRecorder.Code != http.StatusOK {
		t.Fatalf("first evidence publication did not finish successfully: recorder=%v", firstRecorder)
	}
}

func TestPrototypeEvidenceIncompleteBodyDoesNotReservePublicationSlot(t *testing.T) {
	runtimePath := buildPrototypeEvidenceRuntime(t)
	resultsRoot := filepath.Join(t.TempDir(), "published")
	a := &app{
		prototypeEvidenceRuntime: runtimePath,
		prototypeResultsRoot:     resultsRoot,
	}
	body := newGatedEOFBody()
	firstDone := make(chan struct{})
	firstReq := httptest.NewRequest(
		http.MethodPost,
		"/api/v1/prototype/campaigns/evidence",
		body,
	)
	firstReq.Header.Set("Content-Type", "application/json")
	firstRecorder := httptest.NewRecorder()
	go func() {
		defer close(firstDone)
		a.routes().ServeHTTP(firstRecorder, firstReq)
	}()
	t.Cleanup(func() {
		_ = body.Close()
		<-firstDone
	})
	<-body.entered

	secondReq := httptest.NewRequest(
		http.MethodPost,
		"/api/v1/prototype/campaigns/evidence",
		strings.NewReader(`{"schema_version":"aneb-prototype-upload-0.1","campaign_id":"campaign-available"}`),
	)
	secondReq.Header.Set("Content-Type", "application/json")
	secondRecorder := httptest.NewRecorder()
	a.routes().ServeHTTP(secondRecorder, secondReq)

	if secondRecorder.Code != http.StatusOK {
		t.Fatalf("valid evidence publication behind incomplete body status = %d, want %d; body=%s", secondRecorder.Code, http.StatusOK, secondRecorder.Body.String())
	}
}

func TestPrototypeEvidencePublicationFailsClosedOnRuntimeAndReceiptFailures(t *testing.T) {
	runtimePath := buildPrototypeEvidenceRuntime(t)
	resultsRoot := filepath.Join(t.TempDir(), "published")
	a := &app{
		prototypeEvidenceRuntime: runtimePath,
		prototypeResultsRoot:     resultsRoot,
	}
	for _, campaignID := range []string{
		"campaign-fail",
		"campaign-wrong-id",
		"campaign-wrong-hash",
		"campaign-extra-key",
		"campaign-wrong-type",
		"campaign-wrong-status",
		"campaign-wrong-schema",
		"campaign-duplicate-key",
		"campaign-spaced-receipt",
	} {
		t.Run(campaignID, func(t *testing.T) {
			requestBody := `{"schema_version":"aneb-prototype-upload-0.1","campaign_id":"` + campaignID + `"}`
			req := httptest.NewRequest(
				http.MethodPost,
				"/api/v1/prototype/campaigns/evidence",
				strings.NewReader(requestBody),
			)
			req.Header.Set("Content-Type", "application/json")
			recorder := httptest.NewRecorder()

			a.routes().ServeHTTP(recorder, req)

			if recorder.Code != http.StatusInternalServerError {
				t.Fatalf("failed publication status = %d, want %d; body=%s", recorder.Code, http.StatusInternalServerError, recorder.Body.String())
			}
			if got := recorder.Body.String(); got != `{"code":"P018_EVIDENCE_PUBLICATION_FAILED","ok":false}`+"\n" {
				t.Fatalf("failed publication body = %q", got)
			}
			if strings.Contains(recorder.Body.String(), "sensitive") || strings.Contains(recorder.Body.String(), resultsRoot) {
				t.Fatalf("failed publication leaked runtime details: %q", recorder.Body.String())
			}
		})
	}

	invocationBytes, err := os.ReadFile(filepath.Join(resultsRoot, "runtime-invocation.json"))
	if err != nil {
		t.Fatalf("read failed runtime invocation: %v", err)
	}
	var invocation struct {
		InputPath string `json:"input_path"`
	}
	if err := json.Unmarshal(invocationBytes, &invocation); err != nil {
		t.Fatalf("decode failed runtime invocation: %v", err)
	}
	if _, err := os.Lstat(invocation.InputPath); !os.IsNotExist(err) {
		t.Fatalf("failed runtime temporary upload still exists: %q err=%v", invocation.InputPath, err)
	}
}

func TestPrototypeEvidenceFlagsConfigureServerPublication(t *testing.T) {
	runtimePath := buildPrototypeEvidenceRuntime(t)
	tempDir := t.TempDir()
	serverBinaryName := "aneb-server"
	if runtime.GOOS == "windows" {
		serverBinaryName += ".exe"
	}
	serverBinaryPath := filepath.Join(tempDir, serverBinaryName)
	build := exec.Command("go", "build", "-buildvcs=false", "-o", serverBinaryPath, ".")
	build.Dir = "."
	if output, err := build.CombinedOutput(); err != nil {
		t.Fatalf("build server: %v\n%s", err, output)
	}

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserve server port: %v", err)
	}
	address := listener.Addr().String()
	if err := listener.Close(); err != nil {
		t.Fatalf("release server port: %v", err)
	}
	resultsRoot := filepath.Join(tempDir, "published")
	logPath := filepath.Join(tempDir, "server.log")
	logFile, err := os.Create(logPath)
	if err != nil {
		t.Fatalf("create server log: %v", err)
	}
	cmd := exec.Command(
		serverBinaryPath,
		"-prototype-only",
		"-prototype-evidence-runtime", runtimePath,
		"-prototype-results-root", resultsRoot,
		"-addr", address,
		"-data", filepath.Join(tempDir, "data"),
	)
	cmd.Dir = tempDir
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	if err := cmd.Start(); err != nil {
		_ = logFile.Close()
		t.Fatalf("start server: %v", err)
	}
	t.Cleanup(func() {
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
			_, _ = cmd.Process.Wait()
		}
		_ = logFile.Close()
	})

	baseURL := "http://" + address
	client := &http.Client{Timeout: 15 * time.Second}
	deadline := time.Now().Add(10 * time.Second)
	for {
		response, err := client.Get(baseURL + "/api/v1/prototype/capabilities")
		if err == nil && response.StatusCode == http.StatusOK {
			response.Body.Close()
			break
		}
		if response != nil {
			response.Body.Close()
		}
		if time.Now().After(deadline) {
			logBytes, _ := os.ReadFile(logPath)
			t.Fatalf("server did not become ready: %v\n%s", err, logBytes)
		}
		time.Sleep(25 * time.Millisecond)
	}

	upload := `{"schema_version":"aneb-prototype-upload-0.1","campaign_id":"campaign-flags"}`
	response, err := client.Post(
		baseURL+"/api/v1/prototype/campaigns/evidence",
		"application/json",
		strings.NewReader(upload),
	)
	if err != nil {
		t.Fatalf("publish evidence through server flags: %v", err)
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("read evidence response: %v", err)
	}
	if response.StatusCode != http.StatusOK || !strings.Contains(string(responseBody), `"campaign_id":"campaign-flags"`) {
		t.Fatalf("flag-configured evidence response = %d/%s", response.StatusCode, responseBody)
	}
}

func TestPrototypeEvidenceBundledRuntimeSmoke(t *testing.T) {
	runtimePath := os.Getenv("ANEB_EVIDENCE_RUNTIME_SMOKE")
	if runtimePath == "" {
		t.Skip("set ANEB_EVIDENCE_RUNTIME_SMOKE to the bundled evidence runtime")
	}
	if info, err := os.Lstat(runtimePath); err != nil || !info.Mode().IsRegular() {
		t.Fatalf("bundled evidence runtime is not a regular file: %v", err)
	}

	tempDir := t.TempDir()
	sixFileRoot := filepath.Join(tempDir, "six")
	oraclePath := filepath.Join("..", "contracts", "prototype-0.1", "validate_contracts.py")
	emit := exec.Command(
		"python", "-B", oraclePath,
		"emit-bundle",
		"--scenario", "quick-complete",
		"--output", sixFileRoot,
		"--omit-manifest",
	)
	if output, err := emit.CombinedOutput(); err != nil {
		t.Fatalf("emit canonical Android handoff source: %v\n%s", err, output)
	}
	readText := func(name string) string {
		t.Helper()
		value, err := os.ReadFile(filepath.Join(sixFileRoot, name))
		if err != nil {
			t.Fatalf("read %s: %v", name, err)
		}
		return strings.ReplaceAll(string(value), "\r\n", "\n")
	}
	metaText := readText("meta.json")
	var meta struct {
		CampaignID string `json:"campaign_id"`
	}
	if err := json.Unmarshal([]byte(metaText), &meta); err != nil {
		t.Fatalf("decode fixture campaign identity: %v", err)
	}
	uploadBytes, err := json.Marshal(map[string]string{
		"schema_version": "aneb-prototype-upload-0.1",
		"campaign_id":    meta.CampaignID,
		"meta_json":      metaText,
		"events_jsonl":   readText("events.jsonl"),
		"runs_csv":       readText("runs.csv"),
		"summary_csv":    readText("summary.csv"),
	})
	if err != nil {
		t.Fatalf("encode Android handoff: %v", err)
	}
	resultsRoot := filepath.Join(tempDir, "published")
	a := &app{
		prototypeEvidenceRuntime: runtimePath,
		prototypeResultsRoot:     resultsRoot,
	}
	req := httptest.NewRequest(
		http.MethodPost,
		"/api/v1/prototype/campaigns/evidence",
		bytes.NewReader(uploadBytes),
	)
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()

	a.routes().ServeHTTP(recorder, req)

	if recorder.Code != http.StatusOK {
		t.Fatalf("bundled runtime publication status = %d, want %d; body=%s", recorder.Code, http.StatusOK, recorder.Body.String())
	}
	var receipt struct {
		CampaignID        string `json:"campaign_id"`
		ManifestSHA256    string `json:"manifest_sha256"`
		PublicationStatus string `json:"publication_status"`
		SchemaVersion     string `json:"schema_version"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &receipt); err != nil {
		t.Fatalf("decode bundled runtime receipt: %v", err)
	}
	manifestBytes, err := os.ReadFile(filepath.Join(resultsRoot, meta.CampaignID, "manifest.json"))
	if err != nil {
		t.Fatalf("read bundled runtime manifest: %v", err)
	}
	manifestSum := sha256.Sum256(manifestBytes)
	if receipt.CampaignID != meta.CampaignID ||
		receipt.ManifestSHA256 != hex.EncodeToString(manifestSum[:]) ||
		receipt.PublicationStatus != "verified" ||
		receipt.SchemaVersion != "aneb-prototype-publication-receipt-0.1" {
		t.Fatalf("bundled runtime receipt is not manifest-bound: %+v", receipt)
	}
}

func buildPrototypeEvidenceRuntime(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	sourcePath := filepath.Join(root, "main.go")
	binaryName := "fake-evidence-runtime"
	if runtime.GOOS == "windows" {
		binaryName += ".exe"
	}
	binaryPath := filepath.Join(root, binaryName)
	source := `package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

func main() {
	if len(os.Args) != 6 || os.Args[1] != "publish-upload" || os.Args[2] != "--input" || os.Args[4] != "--output-root" {
		os.Exit(31)
	}
	body, err := os.ReadFile(os.Args[3])
	if err != nil { os.Exit(32) }
	var input struct { CampaignID string ` + "`json:\"campaign_id\"`" + ` }
	if json.Unmarshal(body, &input) != nil || input.CampaignID == "" { os.Exit(33) }
	if err := os.MkdirAll(os.Args[5], 0755); err != nil { os.Exit(34) }
	invocation, _ := json.Marshal(map[string]string{"input_path": os.Args[3], "body": string(body)})
	if err := os.WriteFile(filepath.Join(os.Args[5], "runtime-invocation.json"), invocation, 0644); err != nil { os.Exit(36) }
	if input.CampaignID == "campaign-fail" {
		fmt.Fprintf(os.Stdout, "sensitive stdout input=%s\n", os.Args[3])
		fmt.Fprintf(os.Stderr, "sensitive stderr output=%s\n", os.Args[5])
		os.Exit(40)
	}
	if input.CampaignID == "campaign-block" {
		if err := os.WriteFile(filepath.Join(os.Args[5], "runtime-started"), []byte("started\n"), 0600); err != nil { os.Exit(37) }
		deadline := time.Now().Add(10 * time.Second)
		for {
			if _, err := os.Stat(filepath.Join(os.Args[5], "runtime-release")); err == nil { break }
			if time.Now().After(deadline) { os.Exit(38) }
			time.Sleep(10 * time.Millisecond)
		}
	}
	if err := os.Mkdir(filepath.Join(os.Args[5], input.CampaignID), 0755); err != nil { os.Exit(39) }
	manifest := []byte("{\"publication_status\":\"verified\"}\n")
	if err := os.WriteFile(filepath.Join(os.Args[5], input.CampaignID, "manifest.json"), manifest, 0644); err != nil { os.Exit(35) }
	sum := sha256.Sum256(manifest)
	receiptCampaignID := input.CampaignID
	receiptHash := hex.EncodeToString(sum[:])
	receiptStatus := "verified"
	receiptSchema := "aneb-prototype-publication-receipt-0.1"
	switch input.CampaignID {
	case "campaign-wrong-id":
		receiptCampaignID = "campaign-other"
	case "campaign-wrong-hash":
		receiptHash = "0000000000000000000000000000000000000000000000000000000000000000"
	case "campaign-wrong-status":
		receiptStatus = "candidate"
	case "campaign-wrong-schema":
		receiptSchema = "aneb-prototype-publication-receipt-0.2"
	case "campaign-extra-key":
		fmt.Printf("{\"campaign_id\":%q,\"extra\":true,\"manifest_sha256\":%q,\"publication_status\":\"verified\",\"schema_version\":\"aneb-prototype-publication-receipt-0.1\"}\n", input.CampaignID, receiptHash)
		return
	case "campaign-wrong-type":
		fmt.Printf("{\"campaign_id\":7,\"manifest_sha256\":%q,\"publication_status\":\"verified\",\"schema_version\":\"aneb-prototype-publication-receipt-0.1\"}\n", receiptHash)
		return
	case "campaign-duplicate-key":
		fmt.Printf("{\"campaign_id\":%q,\"campaign_id\":%q,\"manifest_sha256\":%q,\"publication_status\":\"verified\",\"schema_version\":\"aneb-prototype-publication-receipt-0.1\"}\n", input.CampaignID, input.CampaignID, receiptHash)
		return
	case "campaign-spaced-receipt":
		fmt.Printf("{ \"campaign_id\":%q,\"manifest_sha256\":%q,\"publication_status\":\"verified\",\"schema_version\":\"aneb-prototype-publication-receipt-0.1\"}\n", input.CampaignID, receiptHash)
		return
	}
	fmt.Printf("{\"campaign_id\":%q,\"manifest_sha256\":%q,\"publication_status\":%q,\"schema_version\":%q}\n", receiptCampaignID, receiptHash, receiptStatus, receiptSchema)
}
`
	if err := os.WriteFile(sourcePath, []byte(source), 0o600); err != nil {
		t.Fatalf("write fake evidence runtime: %v", err)
	}
	cmd := exec.Command("go", "build", "-buildvcs=false", "-o", binaryPath, sourcePath)
	if output, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("build fake evidence runtime: %v\n%s", err, output)
	}
	return binaryPath
}
