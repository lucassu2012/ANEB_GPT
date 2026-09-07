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
	t.Cleanup(func() {
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
			_, _ = cmd.Process.Wait()
		}
		_ = logFile.Close()
	})

	baseURL := "http://" + address
	client := &http.Client{Timeout: 15 * time.Second}
	capabilitiesResponse := waitForPrototypeCapabilities(t, client, baseURL, cmd, logPath)
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

func waitForPrototypeCapabilities(t *testing.T, client *http.Client, baseURL string, cmd *exec.Cmd, logPath string) *http.Response {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		response, err := client.Get(baseURL + "/api/v1/prototype/capabilities")
		if err == nil {
			if response.StatusCode == http.StatusOK {
				return response
			}
			response.Body.Close()
			lastErr = fmt.Errorf("status %d", response.StatusCode)
		} else {
			lastErr = err
		}
		if cmd.ProcessState != nil && cmd.ProcessState.Exited() {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	logBytes, _ := os.ReadFile(logPath)
	t.Fatalf("prototype-only capabilities did not become ready: %v\n%s", lastErr, logBytes)
	return nil
}
