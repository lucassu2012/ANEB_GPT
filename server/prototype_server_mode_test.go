package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestPrototypeOnlyServerStartsWithoutLegacyProfilesAndServesPrototypeAPI(t *testing.T) {
	tempDir := t.TempDir()
	binaryName := "aneb-server"
	if runtime.GOOS == "windows" {
		binaryName += ".exe"
	}
	binaryPath := filepath.Join(tempDir, binaryName)
	build := exec.Command("go", "build", "-buildvcs=false", "-o", binaryPath, ".")
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

	logPath := filepath.Join(tempDir, "server.log")
	logFile, err := os.Create(logPath)
	if err != nil {
		t.Fatalf("create server log: %v", err)
	}
	legacyDataDir := filepath.Join(tempDir, "data")
	cmd := exec.Command(binaryPath,
		"-prototype-only",
		"-addr", address,
		"-data", legacyDataDir,
	)
	// The working directory deliberately has no ../profiles directory. The
	// explicit Prototype-only mode must therefore be sufficient by itself.
	cmd.Dir = tempDir
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	if err := cmd.Start(); err != nil {
		_ = logFile.Close()
		t.Fatalf("start prototype-only server: %v", err)
	}
	commandExit := startCommandWaiter(cmd)
	t.Cleanup(func() {
		stopCommandAndWait(cmd, commandExit)
		_ = logFile.Close()
	})

	baseURL := "http://" + address
	client := &http.Client{Timeout: 15 * time.Second}
	capabilitiesResponse, err := waitForPrototypeCapabilities(t, client, baseURL, commandExit, logPath)
	if err != nil {
		t.Fatalf("wait for prototype capabilities: %v", err)
	}
	defer capabilitiesResponse.Body.Close()
	if capabilitiesResponse.Header.Get("X-Aneb-Server") != serverVersion {
		t.Fatalf("capabilities server identity header = %q, want %q", capabilitiesResponse.Header.Get("X-Aneb-Server"), serverVersion)
	}
	var capabilities prototypeCapabilities
	if err := json.NewDecoder(capabilitiesResponse.Body).Decode(&capabilities); err != nil {
		t.Fatalf("decode capabilities: %v", err)
	}
	if capabilities.SchemaVersion != "aneb-prototype-capabilities-0.1" ||
		capabilities.ProductVersion != "prototype-0.1" ||
		capabilities.ProtocolVersion != "prototype-stream-0.1" ||
		capabilities.ServerVersion != serverVersion {
		t.Fatalf("capability identity = schema %q product %q protocol %q server %q",
			capabilities.SchemaVersion, capabilities.ProductVersion, capabilities.ProtocolVersion, capabilities.ServerVersion)
	}
	binaryBytes, err := os.ReadFile(binaryPath)
	if err != nil {
		t.Fatalf("read built server: %v", err)
	}
	binarySum := sha256.Sum256(binaryBytes)
	if capabilities.ServerBinarySHA256 != hex.EncodeToString(binarySum[:]) {
		t.Fatalf("capability server_binary_sha256 = %q, want built binary identity", capabilities.ServerBinarySHA256)
	}

	serverInfoResponse, err := client.Get(baseURL + "/api/v1/serverinfo")
	if err != nil {
		t.Fatalf("get serverinfo: %v", err)
	}
	defer serverInfoResponse.Body.Close()
	if serverInfoResponse.StatusCode != http.StatusOK || serverInfoResponse.Header.Get("X-Aneb-Server") != serverVersion {
		t.Fatalf("serverinfo status/header = %d/%q", serverInfoResponse.StatusCode, serverInfoResponse.Header.Get("X-Aneb-Server"))
	}
	var info serverInfo
	if err := json.NewDecoder(serverInfoResponse.Body).Decode(&info); err != nil {
		t.Fatalf("decode serverinfo: %v", err)
	}
	if info.Version != serverVersion {
		t.Fatalf("serverinfo version = %q, want %q", info.Version, serverVersion)
	}

	legacyResultResponse, err := client.Post(
		baseURL+"/api/v1/results",
		"application/json",
		strings.NewReader(`{"run_id":"prototype-only-must-not-persist",`+contractFields+`}`),
	)
	if err != nil {
		t.Fatalf("post legacy result to prototype-only server: %v", err)
	}
	legacyResultBody, err := io.ReadAll(legacyResultResponse.Body)
	legacyResultResponse.Body.Close()
	if err != nil {
		t.Fatalf("read prototype-only legacy result response: %v", err)
	}
	if legacyResultResponse.StatusCode != http.StatusNotFound ||
		legacyResultResponse.Header.Get("X-Aneb-Server") != serverVersion {
		t.Fatalf(
			"prototype-only legacy result status/header = %d/%q, want 404/%q (body=%q)",
			legacyResultResponse.StatusCode,
			legacyResultResponse.Header.Get("X-Aneb-Server"),
			serverVersion,
			legacyResultBody,
		)
	}
	if _, err := os.Stat(filepath.Join(legacyDataDir, "results")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("prototype-only legacy result created storage: %v", err)
	}

	runResponse, err := client.Post(
		baseURL+"/api/v1/prototype/runs",
		"application/json",
		strings.NewReader(prototypeValidRunBody(t)),
	)
	if err != nil {
		t.Fatalf("start prototype run: %v", err)
	}
	defer runResponse.Body.Close()
	runBody, err := io.ReadAll(runResponse.Body)
	if err != nil {
		t.Fatalf("read prototype run: %v", err)
	}
	if runResponse.StatusCode != http.StatusOK || runResponse.Header.Get("X-Aneb-Server") != serverVersion {
		t.Fatalf("prototype run status/header = %d/%q, body=%s", runResponse.StatusCode, runResponse.Header.Get("X-Aneb-Server"), runBody)
	}
	if !strings.Contains(string(runBody), "event: run_started\n") || !strings.Contains(string(runBody), "event: done\n") {
		t.Fatalf("prototype run did not complete canonical stream: %s", runBody)
	}
}

type commandWaitResult struct {
	done chan struct{}
	err  error
}

func startCommandWaiter(cmd *exec.Cmd) *commandWaitResult {
	result := &commandWaitResult{done: make(chan struct{})}
	go func() {
		result.err = cmd.Wait()
		close(result.done)
	}()
	return result
}

func stopCommandAndWait(cmd *exec.Cmd, result *commandWaitResult) {
	if cmd.Process == nil {
		return
	}
	_ = cmd.Process.Kill()
	<-result.done
}

func waitForPrototypeCapabilities(t *testing.T, client *http.Client, baseURL string, commandExit *commandWaitResult, logPath string) (*http.Response, error) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		response, err := client.Get(baseURL + "/api/v1/prototype/capabilities")
		if err == nil {
			if response.StatusCode == http.StatusOK {
				return response, nil
			}
			response.Body.Close()
			lastErr = fmt.Errorf("status %d", response.StatusCode)
		} else {
			lastErr = err
		}
		select {
		case <-commandExit.done:
			logBytes, _ := os.ReadFile(logPath)
			return nil, fmt.Errorf("prototype server exited before capabilities became ready: %v\n%s", commandExit.err, logBytes)
		default:
		}
		time.Sleep(25 * time.Millisecond)
	}
	logBytes, _ := os.ReadFile(logPath)
	select {
	case <-commandExit.done:
		return nil, fmt.Errorf("prototype server exited before capabilities became ready: %v\n%s", commandExit.err, logBytes)
	default:
	}
	return nil, fmt.Errorf("prototype-only capabilities did not become ready: %v\n%s", lastErr, logBytes)
}

func TestWaitForPrototypeCapabilitiesReportsEarlyChildExit(t *testing.T) {
	if os.Getenv("ANEB_READINESS_SCENARIO_0930") == "early-exit" {
		tempDir := t.TempDir()
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatalf("reserve readiness port: %v", err)
		}
		address := listener.Addr().String()
		if err := listener.Close(); err != nil {
			t.Fatalf("release readiness port: %v", err)
		}

		logPath := filepath.Join(tempDir, "readiness-child.log")
		logFile, err := os.Create(logPath)
		if err != nil {
			t.Fatalf("create readiness child log: %v", err)
		}
		child := exec.Command(os.Args[0], "-test.run=^TestPrototypeReadinessServerChild$")
		child.Env = append(os.Environ(), "ANEB_READINESS_SERVER_MODE_0930=exit")
		child.Stdout = logFile
		child.Stderr = logFile
		if err := child.Start(); err != nil {
			_ = logFile.Close()
			t.Fatalf("start readiness child: %v", err)
		}
		childExit := startCommandWaiter(child)
		t.Cleanup(func() {
			stopCommandAndWait(child, childExit)
			_ = logFile.Close()
		})

		client := &http.Client{Timeout: 15 * time.Second}
		waitStarted := time.Now()
		_, err = waitForPrototypeCapabilities(t, client, "http://"+address, childExit, logPath)
		if elapsed := time.Since(waitStarted); elapsed >= 4*time.Second {
			t.Fatalf("readiness waited %s after the child exited, want earlier than the 5-second deadline", elapsed)
		}
		if err == nil || !strings.Contains(err.Error(), "prototype server exited before capabilities became ready") {
			t.Fatalf("readiness result = %v, want an early child-exit diagnostic", err)
		}
		fmt.Fprintln(os.Stdout, "EARLY_EXIT_SCENARIO_OK")
		return
	}

	child := exec.Command(os.Args[0], "-test.run=^TestWaitForPrototypeCapabilitiesReportsEarlyChildExit$")
	child.Env = append(os.Environ(), "ANEB_READINESS_SCENARIO_0930=early-exit")
	output, err := child.CombinedOutput()
	if err != nil {
		t.Fatalf("readiness child process failed to verify early exit: %v\n%s", err, output)
	}
	if !strings.Contains(string(output), "EARLY_EXIT_SCENARIO_OK") {
		t.Fatalf("readiness child process did not complete the early-exit assertion:\n%s", output)
	}
}

func TestWaitForPrototypeCapabilitiesPreservesReadyAndCleanup(t *testing.T) {
	tempDir := t.TempDir()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("reserve readiness port: %v", err)
	}
	address := listener.Addr().String()
	if err := listener.Close(); err != nil {
		t.Fatalf("release readiness port: %v", err)
	}

	logFile, err := os.Create(filepath.Join(tempDir, "readiness-server.log"))
	if err != nil {
		t.Fatalf("create readiness server log: %v", err)
	}
	child := exec.Command(os.Args[0], "-test.run=^TestPrototypeReadinessServerChild$")
	child.Env = append(os.Environ(), "ANEB_READINESS_SERVER_MODE_0930=serve", "ANEB_READINESS_SERVER_ADDR_0930="+address)
	child.Stdout = logFile
	child.Stderr = logFile
	if err := child.Start(); err != nil {
		_ = logFile.Close()
		t.Fatalf("start readiness server child: %v", err)
	}
	childExit := startCommandWaiter(child)
	t.Cleanup(func() {
		stopCommandAndWait(child, childExit)
		_ = logFile.Close()
	})

	client := &http.Client{Timeout: 15 * time.Second}
	response, err := waitForPrototypeCapabilities(t, client, "http://"+address, childExit, filepath.Join(tempDir, "readiness-server.log"))
	if err != nil {
		t.Fatalf("ready child result: %v", err)
	}
	response.Body.Close()
}

func TestPrototypeReadinessServerChild(t *testing.T) {
	mode := os.Getenv("ANEB_READINESS_SERVER_MODE_0930")
	if mode == "exit" {
		os.Exit(23)
	}
	if mode != "serve" {
		return
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/v1/prototype/capabilities", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	if err := http.ListenAndServe(os.Getenv("ANEB_READINESS_SERVER_ADDR_0930"), mux); err != nil {
		os.Exit(24)
	}
}
