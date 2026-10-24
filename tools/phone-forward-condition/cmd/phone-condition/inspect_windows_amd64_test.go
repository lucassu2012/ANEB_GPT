//go:build windows && amd64

package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	condition "aneb-phone-forward-condition"
	"aneb-phone-forward-condition/internal/supervisor"
)

func TestInspectionParentPreservesFixedInputAndOwnedWorkerOutcome(t *testing.T) {
	root := t.TempDir()
	request := condition.Request{Format: "phone-forward-condition-1", Mode: condition.Inspect, PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTLSeconds: 1, DownlinkBytesPerSecond: 125000, DriverDirectory: filepath.Join(root, "absent-driver"), OutputDirectory: filepath.Join(root, "fresh-output")}
	data, _ := json.Marshal(request)
	var output bytes.Buffer
	called := false
	err := inspectRequest(data, &output, func() error { return nil }, func(application string, args []string, budget time.Duration) (supervisor.ProcessResult, error) {
		called = true
		if !filepath.IsAbs(application) || budget != 6*time.Second {
			t.Fatalf("wrong owned launch: %s budget=%v", application, budget)
		}
		fixed, err := os.ReadFile(filepath.Join(request.OutputDirectory, "request.json"))
		if err != nil || !bytes.Equal(fixed, data) {
			t.Fatalf("fixed input not preserved: %s err=%v", fixed, err)
		}
		if len(args) != 5 || args[0] != "_worker-inspect" || args[1] != "-config" || args[2] != filepath.Join(request.OutputDirectory, "request.json") || args[3] != "-request-sha256" || len(args[4]) != 64 {
			t.Fatalf("unfixed worker arguments: %q", args)
		}
		if err := writeNewJSON(filepath.Join(request.OutputDirectory, "worker.json"), inspectionOutcome{Format: "phone-forward-inspect-result-1", RequestSHA256: args[4], PID: 123, DriverOpenAttempted: true, OwnedHandleReturned: true, Receipt: condition.Receipt{Mode: condition.Inspect, State: "NO_MATCHING_PACKETS", StopReason: "TTL_EXPIRED", DriverDrained: true, OwnedHandleClosed: true, WorkersJoined: true}}); err != nil {
			t.Fatal(err)
		}
		code := uint32(0)
		return supervisor.ProcessResult{PID: 123, ExitCode: &code, ExitConfirmed: true, AssignedToOwnedJob: true, DeadlineArmedBeforeResume: true, Resumed: true, OwnedTreeEmpty: true, JobClosed: true, ProcessHandleClosed: true, ThreadHandleClosed: true, TimerHandleClosed: true}, nil
	})
	if err != nil || !called {
		t.Fatalf("fixed inspection wrapper failed: %s err=%v", output.String(), err)
	}
	if _, err := os.Stat(filepath.Join(request.OutputDirectory, "supervisor.json")); err != nil {
		t.Fatal(err)
	}
	if err := inspectRequest(data, &output, func() error { return nil }, func(string, []string, time.Duration) (supervisor.ProcessResult, error) {
		t.Fatal("reused output launched another child")
		return supervisor.ProcessResult{}, nil
	}); err == nil {
		t.Fatal("inspection overwrote its first evidence")
	}
}

func TestInspectionRefusesLimitOrMissingApprovalBeforeCreatingRuntimeOutput(t *testing.T) {
	for _, mode := range []condition.Mode{condition.Inspect, condition.Limit} {
		t.Run(string(mode), func(t *testing.T) {
			root := t.TempDir()
			request := condition.Request{Format: "phone-forward-condition-1", Mode: mode, PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTLSeconds: 1, DownlinkBytesPerSecond: 125000, DriverDirectory: root, OutputDirectory: filepath.Join(root, "must-not-create")}
			data, _ := json.Marshal(request)
			err := inspectRequest(data, &bytes.Buffer{}, func() error { return errors.New("not elevated") }, func(string, []string, time.Duration) (supervisor.ProcessResult, error) {
				t.Fatal("unapproved execution launched a child")
				return supervisor.ProcessResult{}, nil
			})
			if err == nil {
				t.Fatal("unapproved execution accepted")
			}
			if _, err := os.Stat(request.OutputDirectory); !os.IsNotExist(err) {
				t.Fatal("refusal created runtime output")
			}
		})
	}
}

func TestInspectionKeepsPartialEvidenceAndCannotPromoteAWorkerTimeout(t *testing.T) {
	root := t.TempDir()
	request := condition.Request{Format: "phone-forward-condition-1", Mode: condition.Inspect, PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTLSeconds: 1, DownlinkBytesPerSecond: 125000, DriverDirectory: root, OutputDirectory: filepath.Join(root, "partial-output")}
	data, _ := json.Marshal(request)
	err := inspectRequest(data, &bytes.Buffer{}, func() error { return nil }, func(string, []string, time.Duration) (supervisor.ProcessResult, error) {
		return supervisor.ProcessResult{PID: 123, TimedOut: true}, supervisor.ErrBudgetExceeded
	})
	if !errors.Is(err, supervisor.ErrBudgetExceeded) {
		t.Fatalf("worker timeout was hidden: %v", err)
	}
	for _, name := range []string{"request.json", "parent-start.json", "supervisor.json"} {
		if _, err := os.Stat(filepath.Join(request.OutputDirectory, name)); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := os.Stat(filepath.Join(request.OutputDirectory, "worker.json")); !os.IsNotExist(err) {
		t.Fatal("missing worker evidence was fabricated")
	}
}
