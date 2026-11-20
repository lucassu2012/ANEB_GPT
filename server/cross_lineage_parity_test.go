package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

type zeroReader struct{}

func (zeroReader) Read(p []byte) (int, error) {
	clear(p)
	return len(p), nil
}

func resolveGitRepoRoot(t *testing.T) string {
	t.Helper()
	if _, err := exec.LookPath("git"); err != nil {
		t.Skipf("skipping git tree parity: git not in PATH (%v)", err)
	}
	cmd := exec.Command("git", "rev-parse", "--show-toplevel")
	out, err := cmd.Output()
	if err != nil {
		t.Skipf("skipping git tree parity: not inside a git repository (%v)", err)
	}
	return strings.TrimSpace(string(out))
}

func resolveMiw2LineageRef(repoRoot string, candidates []string) (string, bool) {
	for _, ref := range candidates {
		cmd := exec.Command("git", "rev-parse", "--verify", "--quiet", ref+"^{commit}")
		cmd.Dir = repoRoot
		if err := cmd.Run(); err == nil {
			return ref, true
		}
	}
	return "", false
}

func TestResolveMiw2LineageRefReturnsFalseForMissingRefs(t *testing.T) {
	repoRoot := resolveGitRepoRoot(t)
	if ref, ok := resolveMiw2LineageRef(repoRoot, []string{"refs/heads/__nonexistent_miw2_ref__"}); ok {
		t.Fatalf("unexpectedly resolved nonexistent ref %q", ref)
	}
}

func TestCrossLineageGitTreeParity(t *testing.T) {
	repoRoot := resolveGitRepoRoot(t)
	ref, ok := resolveMiw2LineageRef(repoRoot, []string{
		"origin/release/miw2-server-0.8",
		"release/miw2-server-0.8",
		"refs/remotes/origin/release/miw2-server-0.8",
		"refs/heads/release/miw2-server-0.8",
	})
	if !ok {
		t.Skipf("skipping git tree/diff parity: release/miw2-server-0.8 ref not available (shallow CI clone)")
	}

	sharedGoFiles := []string{
		"server/handlers_echo.go",
		"server/profiles.go",
		"server/handlers_stream.go",
		"server/handlers_realtime_sim.go",
		"server/handlers_download.go",
		"server/handlers_artifact.go",
		"server/handlers_upload.go",
		"server/tokengen.go",
		"server/clock.go",
		"server/tls.go",
		"server/h3.go",
		"server/realtime_summary.go",
		"server/tcpinfo.go",
		"server/tcpinfo_linux.go",
		"server/tcpinfo_other.go",
	}

	t.Run("shared_go_files_zero_diff", func(t *testing.T) {
		args := append([]string{"diff", "--exit-code", ref, "--"}, sharedGoFiles...)
		diffCmd := exec.Command("git", args...)
		diffCmd.Dir = repoRoot
		if out, err := diffCmd.CombinedOutput(); err != nil {
			t.Fatalf("shared Go implementation drifted from %s:\n%s", ref, string(out))
		}
		for _, relPath := range sharedGoFiles {
			refBlobCmd := exec.Command("git", "rev-parse", ref+":"+relPath)
			refBlobCmd.Dir = repoRoot
			refBlobOut, err := refBlobCmd.Output()
			if err != nil {
				t.Fatalf("git rev-parse %s:%s: %v", ref, relPath, err)
			}
			workBlobCmd := exec.Command("git", "hash-object", relPath)
			workBlobCmd.Dir = repoRoot
			workBlobOut, err := workBlobCmd.Output()
			if err != nil {
				t.Fatalf("git hash-object %s: %v", relPath, err)
			}
			if got, want := strings.TrimSpace(string(workBlobOut)), strings.TrimSpace(string(refBlobOut)); got != want {
				t.Fatalf("%s blob hash = %s, want %s (%s)", relPath, got, want, ref)
			}
		}
	})

	t.Run("frozen_profiles_parity", func(t *testing.T) {
		identicalProfiles := []string{
			"profiles/basic_network.json",
			"profiles/s1_chat.json",
			"profiles/s2_coding_agent.json",
		}
		args := append([]string{"diff", "--exit-code", ref, "--"}, identicalProfiles...)
		diffCmd := exec.Command("git", args...)
		diffCmd.Dir = repoRoot
		if out, err := diffCmd.CombinedOutput(); err != nil {
			t.Fatalf("frozen profiles drifted from %s:\n%s", ref, string(out))
		}

		// s3_multimodal.json differs intentionally across lineages (miw2 0.3.0 vs prototype 0.2.1),
		// so verify valid JSON schema and profile_id on both lineages.
		refS3Cmd := exec.Command("git", "show", ref+":profiles/s3_multimodal.json")
		refS3Cmd.Dir = repoRoot
		refS3Bytes, err := refS3Cmd.Output()
		if err != nil {
			t.Fatalf("git show %s:profiles/s3_multimodal.json: %v", ref, err)
		}
		var refS3 Profile
		if err := json.Unmarshal(refS3Bytes, &refS3); err != nil {
			t.Fatalf("parse %s s3_multimodal.json: %v", ref, err)
		}
		if refS3.ProfileID != "s3_multimodal" || len(refS3.Phases) == 0 {
			t.Fatalf("invalid %s s3_multimodal.json: %+v", ref, refS3)
		}

		localProfiles, err := loadProfiles(filepath.Join(repoRoot, "profiles"))
		if err != nil {
			t.Fatalf("loadProfiles local: %v", err)
		}
		localS3, ok := localProfiles["s3_multimodal"]
		if !ok || localS3.ProfileID != "s3_multimodal" || len(localS3.Phases) == 0 {
			t.Fatalf("invalid local s3_multimodal.json: %+v", localS3)
		}
	})

	t.Run("main_go_routing_and_tls_parity", func(t *testing.T) {
		refMainCmd := exec.Command("git", "show", ref+":server/main.go")
		refMainCmd.Dir = repoRoot
		refMainBytes, err := refMainCmd.Output()
		if err != nil {
			t.Fatalf("git show %s:server/main.go: %v", ref, err)
		}
		localMainBytes, err := exec.Command("git", "show", "HEAD:server/main.go").Output()
		if err != nil {
			t.Fatalf("git show HEAD:server/main.go: %v", err)
		}
		// Prefer working tree file to catch uncommitted regressions too.
		if workMainBytes, err := os.ReadFile(filepath.Join(repoRoot, "server", "main.go")); err == nil {
			localMainBytes = workMainBytes
		}

		requiredTokens := []string{
			`"/api/v1/echo"`,
			`"/api/v1/profiles"`,
			`"/api/v1/stream"`,
			`"/api/v1/realtime-sim"`,
			`"/api/v1/download"`,
			`"/api/v1/artifact_stream"`,
			`"/api/v1/upload"`,
			"withServerHeader",
			"a.tcpHandler(altSvc)",
		}
		requiredPatterns := []*regexp.Regexp{
			regexp.MustCompile(`ConnContext:\s+connContext`),
			regexp.MustCompile(`TLSNextProto:\s+map\[string\]func\(\*http\.Server,\s*\*tls\.Conn,\s*http\.Handler\)\{\}`),
		}
		refMain := string(refMainBytes)
		localMain := string(localMainBytes)
		for _, tok := range requiredTokens {
			if !strings.Contains(refMain, tok) {
				t.Fatalf("%s server/main.go missing token %q", ref, tok)
			}
			if !strings.Contains(localMain, tok) {
				t.Fatalf("local server/main.go missing token %q", tok)
			}
		}
		for _, pat := range requiredPatterns {
			if !pat.MatchString(refMain) {
				t.Fatalf("%s server/main.go missing pattern %q", ref, pat.String())
			}
			if !pat.MatchString(localMain) {
				t.Fatalf("local server/main.go missing pattern %q", pat.String())
			}
		}
	})
}

func TestCrossLineageSevenEndpointContractParity(t *testing.T) {
	profiles, err := loadProfiles("../profiles")
	if err != nil {
		t.Fatalf("loadProfiles: %v", err)
	}
	a := &app{
		profiles:  profiles,
		dataDir:   t.TempDir(),
		h3Enabled: false,
	}

	tlsConf := &tls.Config{
		Certificates: []tls.Certificate{testTLSCert(t)},
		NextProtos:   []string{"http/1.1"},
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv := newTCPServer(a, ln.Addr().String(), "", tlsConf)
	tlsLn := tls.NewListener(ln, srv.TLSConfig)
	go srv.Serve(tlsLn) //nolint:errcheck — closed in cleanup
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
		_ = tlsLn.Close()
	})

	baseURL := "https://" + ln.Addr().String()
	client := &http.Client{
		Timeout: 15 * time.Second,
		Transport: &http.Transport{
			ForceAttemptHTTP2: true,
			TLSClientConfig: &tls.Config{
				InsecureSkipVerify: true, // self-signed test cert
				NextProtos:         []string{"h2", "http/1.1"},
			},
		},
	}

	assertLineageHeaders := func(t *testing.T, resp *http.Response) {
		t.Helper()
		if got := resp.Header.Get("X-Aneb-Server"); !strings.HasPrefix(got, "aneb-server/") || got != serverVersion {
			t.Fatalf("X-Aneb-Server = %q, want %q", got, serverVersion)
		}
		if got := resp.Header.Get("X-Aneb-Proto"); got != "HTTP/1.1;via=tcp" {
			t.Fatalf("X-Aneb-Proto = %q, want HTTP/1.1;via=tcp", got)
		}
	}

	t.Run("tls_http11_pinning_and_sni_routing", func(t *testing.T) {
		resp, err := client.Get(baseURL + "/api/v1/profiles")
		if err != nil {
			t.Fatalf("GET /profiles: %v", err)
		}
		defer resp.Body.Close()
		if resp.Proto != "HTTP/1.1" {
			t.Fatalf("resp.Proto = %q, want HTTP/1.1 (TLSNextProto pinning)", resp.Proto)
		}
		if resp.TLS == nil {
			t.Fatal("expected TLS connection state")
		}
		if resp.TLS.NegotiatedProtocol == "h2" {
			t.Fatalf("unexpected ALPN h2 on TCP server")
		}
		assertLineageHeaders(t, resp)

		defCert, defKey := writeTestCertFiles(t)
		ipCert, ipKey := writeTestCertFiles(t)
		dualConf, err := newTLSConfig(defCert, defKey, ipCert, ipKey)
		if err != nil {
			t.Fatalf("newTLSConfig: %v", err)
		}
		cEmpty, err := dualConf.GetCertificate(&tls.ClientHelloInfo{ServerName: ""})
		if err != nil || cEmpty == nil {
			t.Fatalf("empty SNI GetCertificate failed: %v", err)
		}
		cIP, err := dualConf.GetCertificate(&tls.ClientHelloInfo{ServerName: "120.77.174.207"})
		if err != nil || cIP == nil {
			t.Fatalf("IP SNI GetCertificate failed: %v", err)
		}
		cNamed, err := dualConf.GetCertificate(&tls.ClientHelloInfo{ServerName: sslipHostname})
		if err != nil || cNamed == nil {
			t.Fatalf("named SNI GetCertificate failed: %v", err)
		}
		if bytes.Equal(cEmpty.Certificate[0], cNamed.Certificate[0]) {
			t.Fatal("expected bare-IP and named SNI to select distinct certificates")
		}
		if !bytes.Equal(cEmpty.Certificate[0], cIP.Certificate[0]) {
			t.Fatal("expected empty SNI and IP SNI to share IP-SAN cert")
		}
	})

	t.Run("echo", func(t *testing.T) {
		resp, err := client.Post(baseURL+"/api/v1/echo", "application/json", strings.NewReader(`{"probe":"f16"}`))
		if err != nil {
			t.Fatalf("POST /echo: %v", err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status = %d, want 200", resp.StatusCode)
		}
		assertLineageHeaders(t, resp)
		if ct := resp.Header.Get("Content-Type"); ct != "application/json" {
			t.Fatalf("Content-Type = %q", ct)
		}
		if resp.Header.Get("Content-Length") == "" {
			t.Fatal("missing Content-Length on /echo")
		}
		var e echoResp
		if err := json.NewDecoder(resp.Body).Decode(&e); err != nil {
			t.Fatalf("decode echo: %v", err)
		}
		if e.T1Us < 0 || e.T2Us < e.T1Us || e.AnchorWallUnixNs != anchorWallUnixNs || !strings.Contains(e.Observed, ":") {
			t.Fatalf("unexpected echo payload: %+v", e)
		}

		badMethod, err := client.Get(baseURL + "/api/v1/echo")
		if err != nil {
			t.Fatal(err)
		}
		badMethod.Body.Close()
		if badMethod.StatusCode != http.StatusMethodNotAllowed {
			t.Fatalf("GET /echo status = %d, want 405", badMethod.StatusCode)
		}

		tooBig, err := client.Post(baseURL+"/api/v1/echo", "application/octet-stream", strings.NewReader(strings.Repeat("x", 101)))
		if err != nil {
			t.Fatal(err)
		}
		tooBig.Body.Close()
		if tooBig.StatusCode != http.StatusRequestEntityTooLarge {
			t.Fatalf("POST >100B /echo status = %d, want 413", tooBig.StatusCode)
		}
	})

	t.Run("profiles", func(t *testing.T) {
		resp, err := client.Get(baseURL + "/api/v1/profiles")
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status = %d, want 200", resp.StatusCode)
		}
		assertLineageHeaders(t, resp)
		var body struct {
			ServerVersion string     `json:"server_version"`
			Profiles      []*Profile `json:"profiles"`
		}
		if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		if !strings.HasPrefix(body.ServerVersion, "aneb-server/") {
			t.Fatalf("server_version = %q", body.ServerVersion)
		}
		var gotIDs []string
		for _, p := range body.Profiles {
			gotIDs = append(gotIDs, p.ProfileID)
		}
		wantIDs := []string{"basic_network", "s1_chat", "s2_coding_agent", "s3_multimodal"}
		if !slices.Equal(gotIDs, wantIDs) {
			t.Fatalf("profile IDs = %v, want %v", gotIDs, wantIDs)
		}

		badMethod, err := client.Post(baseURL+"/api/v1/profiles", "application/json", nil)
		if err != nil {
			t.Fatal(err)
		}
		badMethod.Body.Close()
		if badMethod.StatusCode != http.StatusMethodNotAllowed {
			t.Fatalf("POST /profiles status = %d, want 405", badMethod.StatusCode)
		}
	})

	t.Run("stream", func(t *testing.T) {
		resp, err := client.Get(baseURL + "/api/v1/stream?tokens=4&rate_tps=2000&seed=42&ttft_inject_us=5000&tokens_per_frame=2")
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status = %d, want 200", resp.StatusCode)
		}
		assertLineageHeaders(t, resp)
		if ct := resp.Header.Get("Content-Type"); ct != "text/event-stream" {
			t.Fatalf("Content-Type = %q", ct)
		}
		if cc := resp.Header.Get("Cache-Control"); cc != "no-cache" {
			t.Fatalf("Cache-Control = %q", cc)
		}
		if xa := resp.Header.Get("X-Accel-Buffering"); xa != "no" {
			t.Fatalf("X-Accel-Buffering = %q", xa)
		}
		raw, err := io.ReadAll(resp.Body)
		if err != nil {
			t.Fatal(err)
		}
		frames := parseSSE(t, string(raw))
		if len(frames) != 6 {
			t.Fatalf("got %d SSE frames, want 6", len(frames))
		}
		if !strings.HasPrefix(frames[0].comment, "prelude ") {
			t.Fatalf("missing prelude: %+v", frames[0])
		}
		var prelude struct {
			SrvTsUs          int64  `json:"srv_ts_us"`
			AnchorWallUnixNs int64  `json:"anchor_wall_unix_ns"`
			TtftInjectUs     int64  `json:"ttft_inject_us"`
			Observed         string `json:"observed"`
		}
		if err := json.Unmarshal([]byte(strings.TrimPrefix(frames[0].comment, "prelude ")), &prelude); err != nil {
			t.Fatal(err)
		}
		if prelude.SrvTsUs <= 0 || prelude.AnchorWallUnixNs != anchorWallUnixNs || prelude.TtftInjectUs != 5000 || prelude.Observed == "" {
			t.Fatalf("unexpected stream prelude: %+v", prelude)
		}
		for seq := 0; seq < 4; seq++ {
			if frames[seq+1].event != "token" {
				t.Fatalf("frame %d event = %q", seq+1, frames[seq+1].event)
			}
			var tok tokenData
			if err := json.Unmarshal([]byte(frames[seq+1].data), &tok); err != nil {
				t.Fatal(err)
			}
			if tok.Seq != seq {
				t.Fatalf("tok.Seq = %d, want %d", tok.Seq, seq)
			}
			if _, err := base64.StdEncoding.DecodeString(tok.Payload); err != nil {
				t.Fatalf("invalid base64 payload: %v", err)
			}
		}
		if frames[5].event != "summary" {
			t.Fatalf("last frame = %q, want summary", frames[5].event)
		}
		var summary struct {
			Tokens         int     `json:"tokens"`
			TokensPerFrame int     `json:"tokens_per_frame"`
			FramesFlushed  int     `json:"frames_flushed"`
			FlushReturnUs  []int64 `json:"flush_return_us"`
			TimerLateUs    []int64 `json:"timer_late_us"`
			FlushBlockUs   []int64 `json:"flush_block_us"`
			CarryoverUs    []int64 `json:"carryover_us"`
		}
		if err := json.Unmarshal([]byte(frames[5].data), &summary); err != nil {
			t.Fatal(err)
		}
		if summary.Tokens != 4 || summary.TokensPerFrame != 2 || summary.FramesFlushed != 2 ||
			len(summary.FlushReturnUs) != 4 || len(summary.TimerLateUs) != 4 ||
			len(summary.FlushBlockUs) != 4 || len(summary.CarryoverUs) != 4 {
			t.Fatalf("unexpected stream summary: %+v", summary)
		}

		for _, tc := range []struct {
			method, url string
			wantStatus  int
		}{
			{http.MethodPost, baseURL + "/api/v1/stream", http.StatusMethodNotAllowed},
			{http.MethodGet, baseURL + "/api/v1/stream?tokens=0", http.StatusBadRequest},
			{http.MethodGet, baseURL + "/api/v1/stream?tokens=4&inject=truncate:2", http.StatusForbidden},
		} {
			req, _ := http.NewRequest(tc.method, tc.url, nil)
			r2, err := client.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			r2.Body.Close()
			if r2.StatusCode != tc.wantStatus {
				t.Fatalf("%s %s status = %d, want %d", tc.method, tc.url, r2.StatusCode, tc.wantStatus)
			}
		}
	})

	t.Run("realtime_sim", func(t *testing.T) {
		wsURL := "wss" + strings.TrimPrefix(baseURL, "https") + "/api/v1/realtime-sim"
		dialer := websocket.Dialer{
			TLSClientConfig: &tls.Config{
				InsecureSkipVerify: true,
				NextProtos:         []string{"http/1.1"},
			},
			HandshakeTimeout: 5 * time.Second,
		}
		conn, upgradeResp, err := dialer.Dial(wsURL, nil)
		if err != nil {
			t.Fatalf("WS dial: %v", err)
		}
		defer conn.Close()
		if upgradeResp.StatusCode != http.StatusSwitchingProtocols {
			t.Fatalf("WS status = %d, want 101", upgradeResp.StatusCode)
		}
		if strings.ToLower(upgradeResp.Header.Get("Upgrade")) != "websocket" {
			t.Fatalf("WS Upgrade = %q, want websocket", upgradeResp.Header.Get("Upgrade"))
		}

		plan := realtimeSessionPlan{
			ContractVersion: realtimeSessionContract,
			SessionID:       "f16-parity-session",
			Seed:            7,
			SetupMs:         1,
			FrameMs:         20,
			Turns: []realtimeTurnPlan{{
				TurnID:                "turn-0",
				TurnIndex:             0,
				StartAfterPreviousMs:  0,
				UplinkFrames:          2,
				UplinkFrameBytes:      24,
				ResponseWaitMs:        1,
				PlannedDownlinkFrames: 2,
				DownlinkFrameBytes:    32,
			}},
		}
		if err := conn.WriteJSON(plan); err != nil {
			t.Fatal(err)
		}
		ready := readRealtimeTestControl(t, conn)
		if ready["type"] != "session_ready" || ready["observed"] == "" {
			t.Fatalf("unexpected session_ready: %#v", ready)
		}
		if err := conn.WriteJSON(realtimeControl{Type: "ping", PingID: 1}); err != nil {
			t.Fatal(err)
		}
		pong := readRealtimeTestControl(t, conn)
		if pong["type"] != "pong" || pong["ping_id"] != float64(1) {
			t.Fatalf("unexpected pong: %#v", pong)
		}
		if err := conn.WriteJSON(realtimeControl{Type: "turn_start", TurnID: "turn-0", TurnIndex: 0}); err != nil {
			t.Fatal(err)
		}
		for seq := 0; seq < 2; seq++ {
			if err := conn.WriteMessage(websocket.BinaryMessage, encodeRealtimeTestUplink(0, seq, 24)); err != nil {
				t.Fatal(err)
			}
		}
		if err := conn.WriteJSON(realtimeControl{Type: "speech_commit", TurnID: "turn-0", TurnIndex: 0}); err != nil {
			t.Fatal(err)
		}

		dlFrames := 0
		var turnSummary, sessionSummary map[string]any
		deadline := time.Now().Add(5 * time.Second)
		for sessionSummary == nil && time.Now().Before(deadline) {
			msgType, data, err := conn.ReadMessage()
			if err != nil {
				t.Fatalf("WS read: %v", err)
			}
			if msgType == websocket.BinaryMessage {
				if len(data) < 18 || string(data[:4]) != "ANED" {
					t.Fatalf("invalid ANED downlink frame len=%d", len(data))
				}
				dlFrames++
				continue
			}
			var ctrl map[string]any
			if err := json.Unmarshal(data, &ctrl); err != nil {
				t.Fatal(err)
			}
			switch ctrl["type"] {
			case "turn_summary":
				turnSummary = ctrl
			case "session_summary":
				sessionSummary = ctrl
			}
		}
		if dlFrames != 2 || turnSummary == nil || turnSummary["protocol_ok"] != true || sessionSummary == nil || sessionSummary["protocol_ok"] != true {
			t.Fatalf("unexpected realtime session outcome: dlFrames=%d turn=%#v session=%#v", dlFrames, turnSummary, sessionSummary)
		}

		// Negative checks: POST -> 405, Origin -> 403, bad query -> 400
		postResp, err := client.Post(baseURL+"/api/v1/realtime-sim", "application/json", nil)
		if err != nil {
			t.Fatal(err)
		}
		postResp.Body.Close()
		if postResp.StatusCode != http.StatusMethodNotAllowed {
			t.Fatalf("POST /realtime-sim = %d, want 405", postResp.StatusCode)
		}
		assertLineageHeaders(t, postResp)

		badQueryResp, err := client.Get(baseURL + "/api/v1/realtime-sim?controlled_disconnect_after_turn=bad")
		if err != nil {
			t.Fatal(err)
		}
		badQueryResp.Body.Close()
		if badQueryResp.StatusCode != http.StatusBadRequest {
			t.Fatalf("bad query /realtime-sim = %d, want 400", badQueryResp.StatusCode)
		}
		assertLineageHeaders(t, badQueryResp)

		_, originResp, err := dialer.Dial(wsURL, http.Header{"Origin": []string{"https://evil.example"}})
		if err == nil {
			t.Fatal("expected WS dial with Origin header to fail")
		}
		if originResp == nil || originResp.StatusCode != http.StatusForbidden {
			t.Fatalf("WS Origin response = %#v, want 403", originResp)
		}
		assertLineageHeaders(t, originResp)
	})

	t.Run("download", func(t *testing.T) {
		const total = 65543
		resp, err := client.Get(baseURL + "/api/v1/download?bytes=65543&chunk_kb=16")
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status = %d, want 200", resp.StatusCode)
		}
		assertLineageHeaders(t, resp)
		if resp.Header.Get("Content-Type") != "application/octet-stream" ||
			resp.Header.Get("Content-Length") != "65543" ||
			resp.Header.Get("Content-Encoding") != "identity" ||
			resp.Header.Get("Cache-Control") != "no-store, no-cache, must-revalidate" ||
			resp.Header.Get("X-Aneb-Download-Bytes") != "65543" {
			t.Fatalf("unexpected download headers: %+v", resp.Header)
		}
		payload, err := io.ReadAll(resp.Body)
		if err != nil {
			t.Fatal(err)
		}
		if len(payload) != total {
			t.Fatalf("download len = %d, want %d", len(payload), total)
		}
		chunkSize := 16 << 10
		for i, b := range payload {
			want := byte(((i%chunkSize)*31 + 17) & 0xff)
			if b != want {
				t.Fatalf("byte[%d] = %d, want %d", i, b, want)
			}
		}

		for _, tc := range []struct {
			method, url string
			wantStatus  int
		}{
			{http.MethodPost, baseURL + "/api/v1/download", http.StatusMethodNotAllowed},
			{http.MethodGet, baseURL + "/api/v1/download?bytes=0", http.StatusBadRequest},
			{http.MethodGet, baseURL + "/api/v1/download?chunk_kb=2048", http.StatusBadRequest},
			{http.MethodGet, baseURL + "/api/v1/download?profile=nope", http.StatusBadRequest},
		} {
			req, _ := http.NewRequest(tc.method, tc.url, nil)
			r2, err := client.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			r2.Body.Close()
			if r2.StatusCode != tc.wantStatus {
				t.Fatalf("%s %s status = %d, want %d", tc.method, tc.url, r2.StatusCode, tc.wantStatus)
			}
		}
	})

	t.Run("artifact_stream", func(t *testing.T) {
		resp, err := client.Get(baseURL + "/api/v1/artifact_stream?bytes=40960&chunk_kb=16&cadence_bps=1000000000&class=doc&seed=7")
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status = %d, want 200", resp.StatusCode)
		}
		assertLineageHeaders(t, resp)
		if resp.Header.Get("Content-Type") != "text/event-stream" ||
			resp.Header.Get("Cache-Control") != "no-cache" ||
			resp.Header.Get("X-Accel-Buffering") != "no" {
			t.Fatalf("unexpected artifact_stream headers: %+v", resp.Header)
		}
		raw, err := io.ReadAll(resp.Body)
		if err != nil {
			t.Fatal(err)
		}
		frames := parseSSE(t, string(raw))
		if len(frames) != 5 {
			t.Fatalf("got %d artifact frames, want 5 (prelude + 3 chunks + summary)", len(frames))
		}
		var pre struct {
			ArtifactClass string `json:"artifact_class"`
			TotalBytes    int64  `json:"total_bytes"`
			CadenceBps    int64  `json:"cadence_bps"`
		}
		if err := json.Unmarshal([]byte(strings.TrimPrefix(frames[0].comment, "prelude ")), &pre); err != nil {
			t.Fatal(err)
		}
		if pre.ArtifactClass != "doc" || pre.TotalBytes != 40960 || pre.CadenceBps != 1000000000 {
			t.Fatalf("unexpected artifact prelude: %+v", pre)
		}
		wantSizes := []int{16384, 16384, 8192}
		for i, wantSize := range wantSizes {
			if frames[i+1].event != "chunk" {
				t.Fatalf("frame %d event = %q, want chunk", i+1, frames[i+1].event)
			}
			var chunk struct {
				Seq     int    `json:"seq"`
				Bytes   int    `json:"bytes"`
				Payload string `json:"payload"`
			}
			if err := json.Unmarshal([]byte(frames[i+1].data), &chunk); err != nil {
				t.Fatal(err)
			}
			if chunk.Seq != i || chunk.Bytes != wantSize {
				t.Fatalf("chunk %d = %+v, want bytes=%d", i, chunk, wantSize)
			}
			decoded, err := base64.StdEncoding.DecodeString(chunk.Payload)
			if err != nil || len(decoded) != wantSize {
				t.Fatalf("chunk %d base64 len=%d err=%v", i, len(decoded), err)
			}
		}
		if frames[4].event != "summary" {
			t.Fatalf("last frame = %q, want summary", frames[4].event)
		}
		var sum struct {
			TotalBytes    int64   `json:"total_bytes"`
			Chunks        int     `json:"chunks"`
			FlushReturnUs []int64 `json:"flush_return_us"`
			TimerLateUs   []int64 `json:"timer_late_us"`
		}
		if err := json.Unmarshal([]byte(frames[4].data), &sum); err != nil {
			t.Fatal(err)
		}
		if sum.TotalBytes != 40960 || sum.Chunks != 3 || len(sum.FlushReturnUs) != 3 || len(sum.TimerLateUs) != 3 {
			t.Fatalf("unexpected artifact summary: %+v", sum)
		}

		for _, tc := range []struct {
			method, url string
			wantStatus  int
		}{
			{http.MethodPost, baseURL + "/api/v1/artifact_stream", http.StatusMethodNotAllowed},
			{http.MethodGet, baseURL + "/api/v1/artifact_stream?chunk_kb=1", http.StatusBadRequest},
			{http.MethodGet, baseURL + "/api/v1/artifact_stream?cadence_bps=1", http.StatusBadRequest},
		} {
			req, _ := http.NewRequest(tc.method, tc.url, nil)
			r2, err := client.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			r2.Body.Close()
			if r2.StatusCode != tc.wantStatus {
				t.Fatalf("%s %s status = %d, want %d", tc.method, tc.url, r2.StatusCode, tc.wantStatus)
			}
		}
	})

	t.Run("upload", func(t *testing.T) {
		const bodySize = 192 << 10
		resp, err := client.Post(baseURL+"/api/v1/upload?run=f16-parity", "application/octet-stream", bytes.NewReader(make([]byte, bodySize)))
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status = %d, want 200", resp.StatusCode)
		}
		assertLineageHeaders(t, resp)
		if ct := resp.Header.Get("Content-Type"); ct != "application/json" {
			t.Fatalf("Content-Type = %q", ct)
		}
		var up uploadResponse
		if err := json.NewDecoder(resp.Body).Decode(&up); err != nil {
			t.Fatal(err)
		}
		if up.Bytes != bodySize || up.Observed == "" || len(up.ChunkUs) < 3 {
			t.Fatalf("unexpected upload resp: %+v", up)
		}
		prev := up.RecvStartUs
		for _, ts := range up.ChunkUs {
			if ts < prev {
				t.Fatalf("non-monotonic chunk_us %d < %d", ts, prev)
			}
			prev = ts
		}
		if up.RecvEndUs < prev {
			t.Fatalf("recv_end_us %d < last chunk_us %d", up.RecvEndUs, prev)
		}

		badMethod, err := client.Get(baseURL + "/api/v1/upload")
		if err != nil {
			t.Fatal(err)
		}
		badMethod.Body.Close()
		if badMethod.StatusCode != http.StatusMethodNotAllowed {
			t.Fatalf("GET /upload status = %d, want 405", badMethod.StatusCode)
		}

		// Stream 64 MiB + 1 byte via zeroReader so memory remains O(64 KiB).
		req, err := http.NewRequest(http.MethodPost, baseURL+"/api/v1/upload", io.LimitReader(zeroReader{}, uploadMaxBytes+1))
		if err != nil {
			t.Fatal(err)
		}
		tooBig, err := client.Do(req)
		if err != nil {
			t.Fatalf("POST >64MiB /upload: %v", err)
		}
		tooBig.Body.Close()
		if tooBig.StatusCode != http.StatusRequestEntityTooLarge {
			t.Fatalf("POST >64MiB /upload status = %d, want 413", tooBig.StatusCode)
		}
	})

	t.Run("h3_proto_evidence", func(t *testing.T) {
		h3App := &app{profiles: profiles, dataDir: t.TempDir(), h3Enabled: true}
		h3Client, h3Base := startH3TestServer(t, h3App)

		echoResp, err := h3Client.Post(h3Base+"/api/v1/echo", "application/json", strings.NewReader(`{"probe":"f16-h3"}`))
		if err != nil {
			t.Fatalf("h3 POST /echo: %v", err)
		}
		echoResp.Body.Close()
		if got := echoResp.Header.Get("X-Aneb-Proto"); got != "HTTP/3.0;via=h3-server" {
			t.Fatalf("h3 /echo X-Aneb-Proto = %q", got)
		}

		profResp, err := h3Client.Get(h3Base + "/api/v1/profiles")
		if err != nil {
			t.Fatalf("h3 GET /profiles: %v", err)
		}
		profResp.Body.Close()
		if got := profResp.Header.Get("X-Aneb-Proto"); got != "HTTP/3.0;via=h3-server" {
			t.Fatalf("h3 /profiles X-Aneb-Proto = %q", got)
		}
	})
}
