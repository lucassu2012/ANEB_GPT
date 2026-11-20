//go:build windows && amd64

package supervisor

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

type ProcessResult struct {
	PID                       uint32
	ExitCode                  *uint32
	TimedOut                  bool
	ExitConfirmed             bool
	AssignedToOwnedJob        bool
	DeadlineArmedBeforeResume bool
	Resumed                   bool
	OwnedTreeEmpty            bool
	JobClosed                 bool
	ProcessHandleClosed       bool
	ThreadHandleClosed        bool
	TimerHandleClosed         bool
}

var ErrBudgetExceeded = errors.New("owned child exceeded the parent deadline")

const (
	createSuspended                     = 0x00000004
	createNoWindow                      = 0x08000000
	jobObjectExtendedLimitInformation   = 9
	jobObjectBasicAccountingInformation = 1
	jobLimitKillOnClose                 = 0x00002000
	terminationExitCode                 = uint32(0xe0000001)
	cleanupBudget                       = 500 * time.Millisecond
)

var (
	kernel32     = syscall.NewLazyDLL("kernel32.dll")
	createJob    = kernel32.NewProc("CreateJobObjectW")
	setJob       = kernel32.NewProc("SetInformationJobObject")
	assignJob    = kernel32.NewProc("AssignProcessToJobObject")
	queryJob     = kernel32.NewProc("QueryInformationJobObject")
	terminateJob = kernel32.NewProc("TerminateJobObject")
	resumeThread = kernel32.NewProc("ResumeThread")
	createTimer  = kernel32.NewProc("CreateWaitableTimerW")
	setTimer     = kernel32.NewProc("SetWaitableTimer")
	waitMultiple = kernel32.NewProc("WaitForMultipleObjects")
	// A system-boundary seam lets the deadline test model a slow native call.
	createTimerCall = createTimer.Call
)

type jobBasicLimit struct {
	ProcessUserTime    int64
	JobUserTime        int64
	LimitFlags         uint32
	MinimumWorkingSet  uintptr
	MaximumWorkingSet  uintptr
	ActiveProcessLimit uint32
	Affinity           uintptr
	PriorityClass      uint32
	SchedulingClass    uint32
}

type ioCounters struct {
	ReadOperations, WriteOperations, OtherOperations uint64
	ReadBytes, WriteBytes, OtherBytes                uint64
}

type jobExtendedLimit struct {
	Basic                                                      jobBasicLimit
	IO                                                         ioCounters
	ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory uintptr
}

type jobAccounting struct {
	TotalUserTime, TotalKernelTime, PeriodUserTime, PeriodKernelTime      int64
	PageFaults, TotalProcesses, ActiveProcesses, TotalTerminatedProcesses uint32
}

func winError(operation string, original error) error {
	if original == nil || original == syscall.Errno(0) {
		original = errors.New("Windows returned failure without an extended error")
	}
	return fmt.Errorf("%s: %w", operation, original)
}

func requireTrue(operation string, result uintptr, original error) error {
	if result != 0 {
		return nil
	}
	return winError(operation, original)
}

func remainingMillis(deadline time.Time) uint32 {
	remaining := time.Until(deadline)
	if remaining <= 0 {
		return 0
	}
	return uint32((remaining + time.Millisecond - 1) / time.Millisecond)
}

func activeProcesses(job syscall.Handle) (uint32, error) {
	var info jobAccounting
	r, _, e := queryJob.Call(uintptr(job), jobObjectBasicAccountingInformation, uintptr(unsafe.Pointer(&info)), unsafe.Sizeof(info), 0)
	if err := requireTrue("QueryInformationJobObject", r, e); err != nil {
		return 0, err
	}
	return info.ActiveProcesses, nil
}

// RunOwnedProcess accepts an absolute console-worker EXE, not a shell command.
// It never opens or terminates a process by PID. PID is receipt data only.
// Native process creation is synchronous: a late return is cleaned up without
// resuming, but this function cannot promise to cancel a stuck CreateProcessW.
func RunOwnedProcess(application string, args []string, budget time.Duration) (result ProcessResult, err error) {
	started := time.Now()
	if budget <= 0 || budget > 120*time.Second {
		return result, errors.New("owned process requires 0 < budget <= 120 seconds")
	}
	deadline := started.Add(budget)
	if !filepath.IsAbs(application) || !strings.EqualFold(filepath.Ext(application), ".exe") {
		return result, errors.New("application must be an explicit absolute EXE path")
	}
	file, statErr := os.Stat(application)
	if statErr != nil {
		return result, fmt.Errorf("stat application: %w", statErr)
	}
	if !file.Mode().IsRegular() {
		return result, errors.New("application is not a regular file")
	}
	app, err := syscall.UTF16PtrFromString(application)
	if err != nil {
		return result, fmt.Errorf("application UTF-16: %w", err)
	}
	parts := make([]string, 0, len(args)+1)
	parts = append(parts, syscall.EscapeArg(application))
	for _, arg := range args {
		if strings.ContainsRune(arg, 0) {
			return result, errors.New("argument contains NUL")
		}
		parts = append(parts, syscall.EscapeArg(arg))
	}
	command, err := syscall.UTF16FromString(strings.Join(parts, " "))
	if err != nil {
		return result, fmt.Errorf("command UTF-16: %w", err)
	}
	if len(command) > 32767 {
		return result, errors.New("command exceeds CreateProcessW UTF-16 limit")
	}
	var limits jobExtendedLimit
	if unsafe.Sizeof(limits) != 144 || unsafe.Sizeof(limits.Basic) != 64 || unsafe.Offsetof(limits.IO) != 64 || unsafe.Offsetof(limits.ProcessMemory) != 112 || unsafe.Offsetof(limits.Basic.LimitFlags) != 16 {
		return result, errors.New("unsupported Windows job-object ABI")
	}
	r, _, callErr := createJob.Call(0, 0) // anonymous, non-inheritable Job
	if r == 0 {
		return result, winError("CreateJobObjectW", callErr)
	}
	job := syscall.Handle(r)
	var timer syscall.Handle
	var process syscall.ProcessInformation
	assigned := false

	defer func() {
		cleanupDeadline := time.Now().Add(cleanupBudget)
		if process.Process != 0 {
			if assigned {
				active, queryErr := activeProcesses(job)
				err = errors.Join(err, queryErr)
				if queryErr != nil || active != 0 {
					r, _, e := terminateJob.Call(uintptr(job), uintptr(terminationExitCode))
					if terminateErr := requireTrue("TerminateJobObject(owned job)", r, e); terminateErr != nil {
						err = errors.Join(err, terminateErr)
						// Kill-on-close is the owned fallback, never a PID-based kill.
						if closeErr := syscall.CloseHandle(job); closeErr != nil {
							err = errors.Join(err, fmt.Errorf("CloseHandle(owned job fallback): %w", closeErr))
						} else {
							result.JobClosed = true
							job = 0
						}
					}
				}
			} else if !result.ExitConfirmed {
				// Assignment failed: the Job does not own this suspended root yet.
				// This handle came directly from our successful CreateProcessW.
				err = errors.Join(err, syscall.TerminateProcess(process.Process, terminationExitCode))
			}
			if !result.ExitConfirmed {
				status, waitErr := syscall.WaitForSingleObject(process.Process, remainingMillis(cleanupDeadline))
				if waitErr != nil {
					err = errors.Join(err, fmt.Errorf("wait owned child exit: %w", waitErr))
				} else if status != syscall.WAIT_OBJECT_0 {
					err = errors.Join(err, errors.New("owned child exit was not confirmed within cleanup budget"))
				} else {
					result.ExitConfirmed = true
				}
			}
			if result.ExitConfirmed {
				var code uint32
				if getErr := syscall.GetExitCodeProcess(process.Process, &code); getErr != nil {
					err = errors.Join(err, fmt.Errorf("GetExitCodeProcess: %w", getErr))
				} else {
					result.ExitCode = &code
				}
			}
			if assigned && job != 0 {
				for {
					active, queryErr := activeProcesses(job)
					if queryErr != nil {
						err = errors.Join(err, queryErr)
						break
					}
					if active == 0 {
						result.OwnedTreeEmpty = true
						break
					}
					if !time.Now().Before(cleanupDeadline) {
						err = errors.Join(err, errors.New("owned Job still has active processes after cleanup budget"))
						break
					}
					time.Sleep(time.Millisecond)
				}
			}
		}
		closeOwned := func(handle syscall.Handle, label string, confirmed *bool) {
			if handle == 0 {
				return
			}
			if e := syscall.CloseHandle(handle); e != nil {
				err = errors.Join(err, fmt.Errorf("CloseHandle(%s): %w", label, e))
			} else {
				*confirmed = true
			}
		}
		closeOwned(timer, "owned deadline timer", &result.TimerHandleClosed)
		closeOwned(process.Thread, "owned primary thread", &result.ThreadHandleClosed)
		closeOwned(process.Process, "owned child process", &result.ProcessHandleClosed)
		closeOwned(job, "owned Job", &result.JobClosed)
	}()

	limits.Basic.LimitFlags = jobLimitKillOnClose
	r, _, callErr = setJob.Call(uintptr(job), jobObjectExtendedLimitInformation, uintptr(unsafe.Pointer(&limits)), unsafe.Sizeof(limits))
	if err = requireTrue("SetInformationJobObject(KILL_ON_JOB_CLOSE)", r, callErr); err != nil {
		return result, err
	}
	startup := syscall.StartupInfo{Cb: uint32(unsafe.Sizeof(syscall.StartupInfo{})), Flags: syscall.STARTF_USESHOWWINDOW, ShowWindow: syscall.SW_HIDE}
	if err = syscall.CreateProcess(app, &command[0], nil, nil, false, createSuspended|createNoWindow, nil, nil, &startup, &process); err != nil {
		return result, fmt.Errorf("CreateProcessW(suspended/no-window): %w", err)
	}
	result.PID = process.ProcessId
	r, _, callErr = assignJob.Call(uintptr(job), uintptr(process.Process))
	if err = requireTrue("AssignProcessToJobObject", r, callErr); err != nil {
		return result, err
	}
	assigned = true
	result.AssignedToOwnedJob = true
	remaining := time.Until(deadline)
	if remaining <= 0 {
		result.TimedOut = true
		return result, ErrBudgetExceeded
	}
	r, _, callErr = createTimerCall(0, 0, 0) // anonymous, non-inheritable timer
	if r == 0 {
		return result, winError("CreateWaitableTimerW", callErr)
	}
	timer = syscall.Handle(r)
	// Account for time spent creating the timer; it must not extend the
	// original parent budget. The finite wait below is an independent bound.
	remaining = time.Until(deadline)
	if remaining <= 0 {
		result.TimedOut = true
		return result, ErrBudgetExceeded
	}
	due := -int64((remaining + 99*time.Nanosecond) / (100 * time.Nanosecond))
	r, _, callErr = setTimer.Call(uintptr(timer), uintptr(unsafe.Pointer(&due)), 0, 0, 0, 0)
	if err = requireTrue("SetWaitableTimer(parent deadline)", r, callErr); err != nil {
		return result, err
	}
	result.DeadlineArmedBeforeResume = true
	if !time.Now().Before(deadline) {
		result.TimedOut = true
		return result, ErrBudgetExceeded
	}
	r, _, callErr = resumeThread.Call(uintptr(process.Thread))
	if uint32(r) == 0xffffffff {
		return result, winError("ResumeThread", callErr)
	}
	if uint32(r) != 1 {
		return result, fmt.Errorf("ResumeThread unexpected previous suspend count: %d", uint32(r))
	}
	result.Resumed = true
	handles := [2]syscall.Handle{timer, process.Process} // simultaneous signals favor the deadline
	r, _, callErr = waitMultiple.Call(2, uintptr(unsafe.Pointer(&handles[0])), 0, uintptr(remainingMillis(deadline)))
	switch uint32(r) {
	case syscall.WAIT_OBJECT_0, syscall.WAIT_TIMEOUT:
		result.TimedOut = true
		return result, ErrBudgetExceeded
	case syscall.WAIT_OBJECT_0 + 1:
		result.ExitConfirmed = true
		// An exit observed after the original deadline is conservatively a
		// timeout, even if the parent was merely scheduled late.
		if !time.Now().Before(deadline) {
			result.TimedOut = true
			return result, ErrBudgetExceeded
		}
		var code uint32
		if err = syscall.GetExitCodeProcess(process.Process, &code); err != nil {
			return result, fmt.Errorf("GetExitCodeProcess: %w", err)
		}
		result.ExitCode = &code
		if code != 0 {
			return result, fmt.Errorf("owned child exited with code %d", code)
		}
		return result, nil
	case 0xffffffff:
		return result, winError("WaitForMultipleObjects", callErr)
	default:
		return result, fmt.Errorf("WaitForMultipleObjects unexpected status: 0x%x", uint32(r))
	}
}
