//go:build windows && amd64

package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"syscall"
	"time"
	"unsafe"

	condition "aneb-phone-forward-condition"
	"aneb-phone-forward-condition/internal/supervisor"
)

type inspectionOutcome struct {
	Format              string            `json:"format"`
	RequestSHA256       string            `json:"request_sha256"`
	PID                 int               `json:"pid"`
	DriverOpenAttempted bool              `json:"driver_open_attempted"`
	OwnedHandleReturned bool              `json:"owned_handle_returned"`
	Receipt             condition.Receipt `json:"receipt"`
	Failure             string            `json:"failure,omitempty"`
}

func executeInspection(data []byte, output io.Writer) error {
	return inspectRequest(data, output, func() error { return inspectionPreflight(false) }, supervisor.RunOwnedProcess)
}

func writeNew(path string, data []byte) (err error) {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, f.Close()) }()
	n, err := f.Write(data)
	if err != nil {
		return err
	}
	if n != len(data) {
		return io.ErrShortWrite
	}
	return f.Sync()
}

func writeNewJSON(path string, value any) error {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	return writeNew(path, append(data, '\n'))
}

// This is an operational guard, not a security boundary against an already
// privileged local caller. The parent owns the actual anonymous Job handle;
// IsProcessInJob in the private entry only detects an accidental uncontained run.
func inspectionPreflight(requireJob bool) (err error) {
	token, err := syscall.OpenCurrentProcessToken()
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, token.Close()) }()
	var elevated, returned uint32
	if err = syscall.GetTokenInformation(token, syscall.TokenElevation, (*byte)(unsafe.Pointer(&elevated)), 4, &returned); err != nil {
		return err
	}
	if returned != 4 || elevated == 0 {
		return errors.New("inspection requires an already approved elevated console; no automatic elevation or policy bypass")
	}
	if requireJob {
		process, err := syscall.GetCurrentProcess()
		if err != nil {
			return err
		}
		var contained uint32
		ok, _, callErr := syscall.NewLazyDLL("kernel32.dll").NewProc("IsProcessInJob").Call(uintptr(process), 0, uintptr(unsafe.Pointer(&contained)))
		if ok == 0 {
			return fmt.Errorf("IsProcessInJob: %w", callErr)
		}
		if contained == 0 {
			return errors.New("private inspection worker is not in a Job")
		}
	}
	return nil
}

func lockExecutable(path string) (*os.File, string, error) {
	wide, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return nil, "", err
	}
	handle, err := syscall.CreateFile(wide, syscall.GENERIC_READ, syscall.FILE_SHARE_READ, nil, syscall.OPEN_EXISTING, syscall.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		return nil, "", err
	}
	f := os.NewFile(uintptr(handle), path)
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return nil, "", errors.Join(err, f.Close())
	}
	return f, hex.EncodeToString(h.Sum(nil)), nil
}

// inspectRequest is the one-shot parent. It never opens WinDivert, runs a
// shell, captures payload, elevates itself, or reuses an output directory.
func inspectRequest(data []byte, output io.Writer, eligible func() error, launch func(string, []string, time.Duration) (supervisor.ProcessResult, error)) (err error) {
	request, _, err := condition.ParseRequest(data)
	if err != nil {
		return err
	}
	if request.Mode != condition.Inspect || request.TTLSeconds > 115 {
		return errors.New("only inspect is executable; TTL must be <=115s to fit the fixed 5s worker margin in the 120s supervisor cap")
	}
	if err = eligible(); err != nil {
		return err
	}
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	binary, binarySHA, err := lockExecutable(executable)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, binary.Close()) }()
	// Mkdir, not MkdirAll: an existing path or missing parent is not repaired.
	if err = os.Mkdir(request.OutputDirectory, 0700); err != nil {
		return err
	}
	requestPath := filepath.Join(request.OutputDirectory, "request.json")
	if err = writeNew(requestPath, data); err != nil {
		return err
	}
	hash := sha256.Sum256(data)
	requestSHA := hex.EncodeToString(hash[:])
	if err = writeNewJSON(filepath.Join(request.OutputDirectory, "parent-start.json"), map[string]any{"format": "phone-forward-parent-start-1", "state": "STARTING_NOT_A_RESULT", "request_sha256": requestSHA, "binary_sha256": binarySHA, "pid": os.Getpid(), "worker_margin_seconds": 5, "network_qualified": false}); err != nil {
		return err
	}
	process, launchErr := launch(executable, []string{"_worker-inspect", "-config", requestPath, "-request-sha256", requestSHA}, time.Duration(request.TTLSeconds+5)*time.Second)
	var worker inspectionOutcome
	workerFile, readErr := os.Open(filepath.Join(request.OutputDirectory, "worker.json"))
	if readErr == nil {
		workerData, workerReadErr := io.ReadAll(io.LimitReader(workerFile, (1<<20)+1))
		readErr = errors.Join(workerReadErr, workerFile.Close())
		if len(workerData) > 1<<20 {
			readErr = errors.New("worker result exceeds 1MiB")
		} else if readErr == nil {
			readErr = json.Unmarshal(workerData, &worker)
		}
		if readErr == nil && (worker.Format != "phone-forward-inspect-result-1" || worker.RequestSHA256 != requestSHA) {
			readErr = errors.New("worker result identity mismatch")
		}
	}
	err = errors.Join(launchErr, readErr)
	if err == nil && (process.PID == 0 || process.ExitCode == nil || *process.ExitCode != 0 || !process.ExitConfirmed || !process.OwnedTreeEmpty || !process.AssignedToOwnedJob || !process.DeadlineArmedBeforeResume || !process.Resumed || !process.JobClosed || !process.ProcessHandleClosed || !process.ThreadHandleClosed || !process.TimerHandleClosed || process.TimedOut) {
		err = errors.New("owned inspection worker did not finish with complete supervision receipts")
	}
	if err == nil && (worker.PID != int(process.PID) || !worker.DriverOpenAttempted || !worker.OwnedHandleReturned || worker.Failure != "" || worker.Receipt.Mode != condition.Inspect || worker.Receipt.UnconfirmedUserPackets != 0 || !worker.Receipt.DriverDrained || !worker.Receipt.OwnedHandleClosed || !worker.Receipt.WorkersJoined || (worker.Receipt.State != "NO_MATCHING_PACKETS" && worker.Receipt.State != "OBSERVED_NOT_QUALIFIED")) {
		err = errors.New("worker did not return a completed, unqualified inspection")
	}
	summary := map[string]any{"format": "phone-forward-supervision-1", "request_sha256": requestSHA, "binary_sha256": binarySHA, "process": process, "network_qualified": false, "worker": worker}
	if err != nil {
		summary["failure"] = err.Error()
	}
	writeErr := writeNewJSON(filepath.Join(request.OutputDirectory, "supervisor.json"), summary)
	encodeErr := json.NewEncoder(output).Encode(summary)
	return errors.Join(err, writeErr, encodeErr)
}

func inspectWorker(data []byte, expectedSHA string) (err error) {
	if err := inspectionPreflight(true); err != nil {
		return err
	}
	request, plan, err := condition.ParseRequest(data)
	if err != nil {
		return err
	}
	hash := sha256.Sum256(data)
	if request.Mode != condition.Inspect || request.TTLSeconds > 115 || expectedSHA != hex.EncodeToString(hash[:]) {
		return errors.New("private worker requires the exact fixed inspection request")
	}
	if err := writeNewJSON(filepath.Join(request.OutputDirectory, "worker-start.json"), map[string]any{"format": "phone-forward-worker-start-1", "state": "RUNNING_NOT_A_RESULT", "pid": os.Getpid(), "request_sha256": expectedSHA}); err != nil {
		return err
	}
	result := inspectionOutcome{Format: "phone-forward-inspect-result-1", RequestSHA256: expectedSHA, PID: os.Getpid()}
	result.Receipt, err = condition.Run(context.Background(), plan.Config, condition.Inspect, condition.SystemClock{}, func(plan condition.Plan, mode condition.Mode) (condition.Link, error) {
		result.DriverOpenAttempted = true
		link, openErr := condition.OpenNative(plan, mode, request.DriverDirectory)
		result.OwnedHandleReturned = link != nil
		return link, openErr
	})
	if err != nil {
		result.Failure = err.Error()
	}
	return errors.Join(err, writeNewJSON(filepath.Join(request.OutputDirectory, "worker.json"), result))
}
