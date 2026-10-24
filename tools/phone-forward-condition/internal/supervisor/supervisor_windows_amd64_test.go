//go:build windows && amd64

package supervisor

import (
	"errors"
	"fmt"
	"os"
	"reflect"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
	"unsafe"
)

var quotedArguments = []string{"spaced value", `a"b`, `trailing slash\`, "", "路径Ω"}

func helperArgs(mode string, payload ...string) []string {
	return append([]string{"-test.run=^TestOwnedChildHelper$", "--", "--aneb-owned-child", mode}, payload...)
}

func TestOwnedChildHelper(t *testing.T) {
	for i, arg := range os.Args {
		if arg != "--aneb-owned-child" {
			continue
		}
		if i+1 < len(os.Args) && os.Args[i+1] == "exit" {
			if !reflect.DeepEqual(os.Args[i+2:], quotedArguments) {
				os.Exit(44)
			}
			os.Exit(0)
		}
		if i+1 < len(os.Args) && os.Args[i+1] == "sleep" {
			time.Sleep(5 * time.Second)
			os.Exit(0)
		}
		if i+2 < len(os.Args) && os.Args[i+1] == "exit-at" {
			nanos, err := strconv.ParseInt(os.Args[i+2], 10, 64)
			if err != nil {
				os.Exit(47)
			}
			time.Sleep(time.Until(time.Unix(0, nanos)))
			os.Exit(0)
		}
		if i+1 < len(os.Args) && os.Args[i+1] == "close-job" {
			if exerciseOwnedJobClose() != nil {
				os.Exit(46)
			}
			os.Exit(0)
		}
		os.Exit(45)
	}
	t.Skip("only the explicitly owned child uses this entry")
}

// Supplementary OS-boundary check: this helper itself is already inside the
// public supervisor's <=1.5s owned Job/deadline. It creates only its own child.
func exerciseOwnedJobClose() (err error) {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	r, _, e := createJob.Call(0, 0)
	if r == 0 {
		return winError("CreateJobObjectW(test only)", e)
	}
	job := syscall.Handle(r)
	var child syscall.ProcessInformation
	confirmed := false
	defer func() {
		if child.Process != 0 && !confirmed {
			// Only the HANDLE returned by this helper's successful create.
			_ = syscall.TerminateProcess(child.Process, terminationExitCode)
			_, _ = syscall.WaitForSingleObject(child.Process, 300)
		}
		if child.Thread != 0 {
			err = errors.Join(err, syscall.CloseHandle(child.Thread))
		}
		if child.Process != 0 {
			err = errors.Join(err, syscall.CloseHandle(child.Process))
		}
		if job != 0 {
			err = errors.Join(err, syscall.CloseHandle(job))
		}
	}()
	var limits jobExtendedLimit
	limits.Basic.LimitFlags = jobLimitKillOnClose
	r, _, e = setJob.Call(uintptr(job), jobObjectExtendedLimitInformation, uintptr(unsafe.Pointer(&limits)), unsafe.Sizeof(limits))
	if err = requireTrue("SetInformationJobObject(test only)", r, e); err != nil {
		return err
	}
	app, _ := syscall.UTF16PtrFromString(exe)
	parts := []string{syscall.EscapeArg(exe)}
	for _, arg := range helperArgs("sleep") {
		parts = append(parts, syscall.EscapeArg(arg))
	}
	command, _ := syscall.UTF16FromString(strings.Join(parts, " "))
	startup := syscall.StartupInfo{Cb: uint32(unsafe.Sizeof(syscall.StartupInfo{})), Flags: syscall.STARTF_USESHOWWINDOW, ShowWindow: syscall.SW_HIDE}
	if err = syscall.CreateProcess(app, &command[0], nil, nil, false, createSuspended|createNoWindow, nil, nil, &startup, &child); err != nil {
		return err
	}
	r, _, e = assignJob.Call(uintptr(job), uintptr(child.Process))
	if err = requireTrue("AssignProcessToJobObject(test only)", r, e); err != nil {
		return err
	}
	r, _, e = resumeThread.Call(uintptr(child.Thread))
	if uint32(r) != 1 {
		return fmt.Errorf("test ResumeThread status=%d original=%w", uint32(r), e)
	}
	if err = syscall.CloseHandle(job); err != nil {
		return err
	}
	job = 0
	status, err := syscall.WaitForSingleObject(child.Process, 300)
	if err != nil {
		return err
	}
	if status != syscall.WAIT_OBJECT_0 {
		return errors.New("closing private Job did not confirm own child exit")
	}
	confirmed = true
	return nil
}

func TestClosingOwnedJobEndsItsOwnedChild(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	started := time.Now()
	result, err := RunOwnedProcess(exe, helperArgs("close-job"), 1500*time.Millisecond)
	if err != nil || !result.ExitConfirmed || !result.OwnedTreeEmpty || !result.JobClosed {
		t.Fatalf("owned Job close check failed: result=%+v err=%v", result, err)
	}
	if elapsed := time.Since(started); elapsed > 2*time.Second {
		t.Fatalf("bounded test exceeded 2s: %v", elapsed)
	}
	t.Logf("private Job close killed only its child; outer receipt %+v elapsed=%v", result, time.Since(started))
}

func TestRunOwnedProcessTimeoutTerminatesOnlyItsOwnedJob(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	started := time.Now()
	result, err := RunOwnedProcess(exe, helperArgs("sleep"), 150*time.Millisecond)
	// A slow CreateProcessW may consume the budget before a timer exists.
	// That suspended child must still be terminated and confirmed; no absent
	// handle can truthfully have a successful CloseHandle receipt.
	if !errors.Is(err, ErrBudgetExceeded) || !result.TimedOut || !result.ExitConfirmed || result.ExitCode == nil || *result.ExitCode != terminationExitCode || !result.OwnedTreeEmpty || !result.JobClosed || !result.ProcessHandleClosed || !result.ThreadHandleClosed || (result.Resumed && !result.TimerHandleClosed) {
		t.Fatalf("owned timeout not confirmed: result=%+v err=%v", result, err)
	}
	if elapsed := time.Since(started); elapsed > 2*time.Second {
		t.Fatalf("bounded test exceeded 2s: %v", elapsed)
	}
	t.Logf("own timed child: %+v exit=%d elapsed=%v", result, *result.ExitCode, time.Since(started))
}

func TestRunOwnedProcessOriginalDeadlineIncludesSlowTimerCreation(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	original := createTimerCall
	createTimerCall = func(args ...uintptr) (uintptr, uintptr, error) {
		time.Sleep(700 * time.Millisecond)
		return original(args...)
	}
	t.Cleanup(func() { createTimerCall = original })
	started := time.Now()
	// This own child would voluntarily exit after the original 1s budget
	// but before a relative timer incorrectly extended by its constructor.
	exitAt := started.Add(1100 * time.Millisecond).UnixNano()
	result, err := RunOwnedProcess(exe, helperArgs("exit-at", strconv.FormatInt(exitAt, 10)), time.Second)
	if !errors.Is(err, ErrBudgetExceeded) || !result.TimedOut || !result.ExitConfirmed || result.ExitCode == nil || *result.ExitCode != terminationExitCode || !result.OwnedTreeEmpty || !result.JobClosed {
		t.Fatalf("slow timer creation extended original deadline: result=%+v err=%v", result, err)
	}
	if elapsed := time.Since(started); elapsed > 2*time.Second {
		t.Fatalf("bounded test exceeded 2s: %v", elapsed)
	}
	t.Logf("original deadline retained: %+v exit=%d elapsed=%v", result, *result.ExitCode, time.Since(started))
}

func TestRunOwnedProcessNormalExit(t *testing.T) {
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	result, err := RunOwnedProcess(exe, helperArgs("exit", quotedArguments...), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if result.PID == 0 || result.ExitCode == nil || *result.ExitCode != 0 || !result.ExitConfirmed || result.TimedOut || !result.AssignedToOwnedJob || !result.DeadlineArmedBeforeResume || !result.Resumed || !result.OwnedTreeEmpty || !result.JobClosed || !result.ProcessHandleClosed || !result.ThreadHandleClosed || !result.TimerHandleClosed {
		t.Fatalf("incomplete owned process receipt: %+v", result)
	}
	t.Logf("own normal child: %+v exit=%d", result, *result.ExitCode)
}
