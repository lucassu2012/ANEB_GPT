//go:build windows && amd64

package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
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

func TestLimitParentPreservesFixedModeAndUncalibratedOutcome(t *testing.T) {
	root := t.TempDir()
	request := condition.Request{Format: "phone-forward-condition-1", Mode: condition.Limit, PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTLSeconds: 1, DownlinkBytesPerSecond: 125000, DriverDirectory: filepath.Join(root, "absent-driver"), OutputDirectory: filepath.Join(root, "limit-output")}
	data, _ := json.Marshal(request)
	var output bytes.Buffer
	err := inspectRequest(data, &output, func() error { return nil }, func(application string, args []string, budget time.Duration) (supervisor.ProcessResult, error) {
		if !filepath.IsAbs(application) || budget != 6*time.Second || len(args) != 5 || args[0] != "_worker-limit" {
			t.Fatalf("limit did not retain its owned worker mode: %q %v", args, budget)
		}
		fixed, err := os.ReadFile(args[2])
		if err != nil || !bytes.Equal(fixed, data) {
			t.Fatalf("limit request changed: %s err=%v", fixed, err)
		}
		if err := writeNewJSON(filepath.Join(request.OutputDirectory, "worker.json"), inspectionOutcome{Format: "phone-forward-limit-result-1", RequestSHA256: args[4], PID: 123, DriverOpenAttempted: true, OwnedHandleReturned: true, Receipt: condition.Receipt{Mode: condition.Limit, State: "INJECTED_NOT_CALIBRATED", StopReason: "TTL_EXPIRED", InjectedPackets: 1, InjectedBytes: 40, DriverDrained: true, OwnedHandleClosed: true, WorkersJoined: true}}); err != nil {
			t.Fatal(err)
		}
		code := uint32(0)
		return supervisor.ProcessResult{PID: 123, ExitCode: &code, ExitConfirmed: true, AssignedToOwnedJob: true, DeadlineArmedBeforeResume: true, Resumed: true, OwnedTreeEmpty: true, JobClosed: true, ProcessHandleClosed: true, ThreadHandleClosed: true, TimerHandleClosed: true}, nil
	})
	if err != nil {
		t.Fatalf("fixed limit wrapper failed: %s err=%v", output.String(), err)
	}
	var summary struct {
		NetworkQualified bool              `json:"network_qualified"`
		Worker           inspectionOutcome `json:"worker"`
	}
	if err := json.Unmarshal(output.Bytes(), &summary); err != nil {
		t.Fatal(err)
	}
	if summary.NetworkQualified || summary.Worker.Receipt.Mode != condition.Limit || summary.Worker.Receipt.State != "INJECTED_NOT_CALIBRATED" || summary.Worker.Receipt.PhoneReceivedBytes != nil || summary.Worker.Receipt.DriverDroppedPackets != nil {
		t.Fatalf("limit was relabelled as calibrated phone delivery: %+v", summary)
	}
}

type onePacketWorkerLink struct {
	mu       sync.Mutex
	frame    condition.Frame
	read     bool
	injected []condition.Frame
	stop     chan struct{}
	once     sync.Once
	closed   atomic.Bool
}

func (l *onePacketWorkerLink) Receive() (condition.Frame, error) {
	l.mu.Lock()
	if !l.read {
		l.read = true
		l.mu.Unlock()
		return l.frame, nil
	}
	l.mu.Unlock()
	<-l.stop
	return condition.Frame{}, io.EOF
}

func (l *onePacketWorkerLink) Inject(frame condition.Frame) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.injected = append(l.injected, condition.Frame{Packet: append([]byte(nil), frame.Packet...), Address: frame.Address})
	return len(frame.Packet), nil
}

func (l *onePacketWorkerLink) ShutdownReceive() error {
	l.once.Do(func() { close(l.stop) })
	return nil
}

func (l *onePacketWorkerLink) Close() error { l.closed.Store(true); return nil }

func TestLimitWorkerExercisesRealCoreAndRetainsUnmodifiedPacket(t *testing.T) {
	root := t.TempDir()
	request := condition.Request{Format: "phone-forward-condition-1", Mode: condition.Limit, PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTLSeconds: 1, DownlinkBytesPerSecond: 125000, DriverDirectory: filepath.Join(root, "absent-driver"), OutputDirectory: root}
	data, _ := json.Marshal(request)
	hash := sha256.Sum256(data)
	packet := make([]byte, 20)
	packet[0] = 0x45
	binary.BigEndian.PutUint16(packet[2:4], 20)
	copy(packet[16:20], []byte{192, 0, 2, 8})
	address := condition.Address{TimestampQPC: 123, Word: 1}
	binary.LittleEndian.PutUint32(address.Data[:4], 12)
	address.Data[63] = 0xa5
	link := &onePacketWorkerLink{frame: condition.Frame{Packet: packet, Address: address}, stop: make(chan struct{})}
	err := workerRequest(data, hex.EncodeToString(hash[:]), condition.Limit, func() error { return nil }, condition.SystemClock{}, func(plan condition.Plan, mode condition.Mode, directory string) (condition.Link, error) {
		if mode != condition.Limit || directory != request.DriverDirectory || plan.Filter != "ifIdx == 12 and (ip.DstAddr == 192.0.2.8)" {
			t.Fatalf("worker changed fixed forwarding request: mode=%s directory=%s filter=%s", mode, directory, plan.Filter)
		}
		return link, nil
	})
	if err != nil {
		t.Fatalf("limit worker rejected its fixed request: %v", err)
	}
	stored, err := os.ReadFile(filepath.Join(root, "worker.json"))
	if err != nil {
		t.Fatal(err)
	}
	var result inspectionOutcome
	if err := json.Unmarshal(stored, &result); err != nil {
		t.Fatal(err)
	}
	if result.Format != "phone-forward-limit-result-1" || result.Receipt.Mode != condition.Limit || result.Receipt.State != "INJECTED_NOT_CALIBRATED" || result.Receipt.StopReason != "TTL_EXPIRED" || result.Receipt.InjectedPackets != 1 || !result.Receipt.DriverDrained || !result.Receipt.WorkersJoined || !result.Receipt.OwnedHandleClosed || !link.closed.Load() || result.Receipt.PhoneReceivedBytes != nil {
		t.Fatalf("worker did not retain the actual uncalibrated core result: %+v", result)
	}
	if len(link.injected) != 1 || !bytes.Equal(link.injected[0].Packet, packet) || link.injected[0].Address != address {
		t.Fatal("worker changed or repeated the owned packet")
	}
}

func TestLimitCommandRejectsInspectRequestWithoutCreatingRuntimeOutput(t *testing.T) {
	root := t.TempDir()
	request := condition.Request{Format: "phone-forward-condition-1", Mode: condition.Inspect, PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTLSeconds: 1, DownlinkBytesPerSecond: 125000, DriverDirectory: filepath.Join(root, "absent-driver"), OutputDirectory: filepath.Join(root, "must-not-create")}
	data, _ := json.Marshal(request)
	path := filepath.Join(root, "request.json")
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	err := run([]string{"limit", "-config", path}, &bytes.Buffer{})
	if err == nil || !strings.Contains(err.Error(), "limit request") {
		t.Fatalf("limit command did not enforce its explicit blocking-mode selection: %v", err)
	}
	if _, err := os.Stat(request.OutputDirectory); !os.IsNotExist(err) {
		t.Fatal("mode refusal created runtime output")
	}
}

func TestConditionParentRefusesMissingApprovalBeforeCreatingRuntimeOutput(t *testing.T) {
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

func TestInspectionCommandCannotInferBlockingMode(t *testing.T) {
	root := t.TempDir()
	request := condition.Request{Format: "phone-forward-condition-1", Mode: condition.Limit, PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTLSeconds: 1, DownlinkBytesPerSecond: 125000, DriverDirectory: filepath.Join(root, "absent-driver"), OutputDirectory: filepath.Join(root, "must-not-create")}
	data, _ := json.Marshal(request)
	path := filepath.Join(root, "request.json")
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	err := run([]string{"inspect", "-config", path}, &bytes.Buffer{})
	if err == nil || !strings.Contains(err.Error(), "inspect request") {
		t.Fatalf("inspection could infer blocking behavior from input: %v", err)
	}
	if _, err := os.Stat(request.OutputDirectory); !os.IsNotExist(err) {
		t.Fatal("inspect mode refusal created runtime output")
	}
}

func TestWorkerModeAndHashMismatchNeverReachNativeOpen(t *testing.T) {
	for _, mismatch := range []string{"entry-mode", "request-hash"} {
		t.Run(mismatch, func(t *testing.T) {
			root := t.TempDir()
			request := condition.Request{Format: "phone-forward-condition-1", Mode: condition.Limit, PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTLSeconds: 1, DownlinkBytesPerSecond: 125000, DriverDirectory: root, OutputDirectory: root}
			data, _ := json.Marshal(request)
			hash := sha256.Sum256(data)
			expectedHash := hex.EncodeToString(hash[:])
			expectedMode := condition.Limit
			if mismatch == "entry-mode" {
				expectedMode = condition.Inspect
			} else {
				expectedHash = strings.Repeat("0", 64)
			}
			err := workerRequest(data, expectedHash, expectedMode, func() error { return nil }, condition.SystemClock{}, func(condition.Plan, condition.Mode, string) (condition.Link, error) {
				t.Fatal("mismatched private worker attempted native open")
				return nil, nil
			})
			if err == nil {
				t.Fatal("private worker accepted mismatched identity")
			}
			if _, err := os.Stat(filepath.Join(root, "worker-start.json")); !os.IsNotExist(err) {
				t.Fatal("mismatched worker fabricated a running receipt")
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
