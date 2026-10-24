//go:build windows && amd64

package condition

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sync/atomic"
	"syscall"
	"unsafe"
)

var ErrSniffInjection = errors.New("SNIFF copies must never be injected")

type nativeLink struct {
	mode                                                  Mode
	handle                                                uintptr
	dll                                                   *syscall.DLL // Kept mapped until the one-shot worker process exits.
	pins                                                  []*os.File
	recv, send, shutdown, closeHandle, setParam, getParam *syscall.Proc
	closed                                                atomic.Bool
}

func (l *nativeLink) Inject(frame Frame) (int, error) {
	if l.mode == Inspect || frame.Address.IsSniffed() {
		return 0, ErrSniffInjection
	}
	if l.closed.Load() || l.handle == 0 || l.send == nil {
		return 0, io.ErrClosedPipe
	}
	if len(frame.Packet) == 0 || len(frame.Packet) > 65575 {
		return 0, errors.New("unsupported packet length")
	}
	var written uint32
	ok, _, callErr := l.send.Call(l.handle, uintptr(unsafe.Pointer(&frame.Packet[0])), uintptr(len(frame.Packet)), uintptr(unsafe.Pointer(&written)), uintptr(unsafe.Pointer(&frame.Address)))
	runtime.KeepAlive(frame)
	if ok == 0 {
		return int(written), fmt.Errorf("WinDivertSend: %w", callErr)
	}
	if int(written) != len(frame.Packet) {
		return int(written), io.ErrShortWrite
	}
	return int(written), nil
}

func (l *nativeLink) Receive() (Frame, error) {
	if l.closed.Load() || l.handle == 0 || l.recv == nil {
		return Frame{}, io.ErrClosedPipe
	}
	packet := make([]byte, 65575)
	var received uint32
	var address Address
	ok, _, callErr := l.recv.Call(l.handle, uintptr(unsafe.Pointer(&packet[0])), uintptr(len(packet)), uintptr(unsafe.Pointer(&received)), uintptr(unsafe.Pointer(&address)))
	runtime.KeepAlive(packet)
	runtime.KeepAlive(&address)
	if ok == 0 {
		if errors.Is(callErr, syscall.Errno(232)) {
			return Frame{}, io.EOF
		}
		return Frame{}, fmt.Errorf("WinDivertRecv returned %d bytes: %w", received, callErr)
	}
	if received == 0 || int(received) > len(packet) {
		return Frame{}, fmt.Errorf("invalid receive length: %d", received)
	}
	return Frame{Packet: packet[:received], Address: address}, nil
}

func (l *nativeLink) ShutdownReceive() error {
	if l.closed.Load() || l.handle == 0 || l.shutdown == nil {
		return io.ErrClosedPipe
	}
	ok, _, err := l.shutdown.Call(l.handle, 1) // RECV only: keep Send available for drain.
	if ok == 0 {
		return fmt.Errorf("WinDivertShutdown(RECV): %w", err)
	}
	return nil
}

func (l *nativeLink) Close() error {
	if !l.closed.CompareAndSwap(false, true) || l.handle == 0 || l.closeHandle == nil {
		return io.ErrClosedPipe
	}
	ok, _, err := l.closeHandle.Call(l.handle)
	if ok == 0 {
		return fmt.Errorf("WinDivertClose: %w", err)
	}
	for _, pin := range l.pins {
		if e := pin.Close(); e != nil {
			return e
		}
	}
	// Do not FreeLibrary while a timed-out native call could still return into it.
	// The independently bounded worker process owns this module's final lifetime.
	return nil
}

func OpenNative(plan Plan, mode Mode, driverDirectory string) (Link, error) {
	validated, err := NewPlan(plan.Config)
	if err != nil {
		return nil, err
	}
	plan = validated // Never use an externally altered Filter string.
	if mode != Inspect && mode != Limit {
		return nil, errors.New("unsupported capture mode")
	}
	directory, err := filepath.Abs(driverDirectory)
	if err != nil {
		return nil, err
	}
	directory, err = filepath.EvalSymlinks(directory)
	if err != nil {
		return nil, err
	}
	var pins []*os.File
	var dll *syscall.DLL
	handedOff := false
	defer func() {
		if !handedOff {
			if dll != nil {
				_ = dll.Release()
			}
			for _, pin := range pins {
				_ = pin.Close()
			}
		}
	}()
	for _, pin := range []struct {
		name  string
		bytes int64
		sha   string
	}{
		{"WinDivert.dll", 47616, "c1e060ee19444a259b2162f8af0f3fe8c4428a1c6f694dce20de194ac8d7d9a2"},
		{"WinDivert64.sys", 94144, "8da085332782708d8767bcace5327a6ec7283c17cfb85e40b03cd2323a90ddc2"},
	} {
		path := filepath.Join(directory, pin.name)
		wide, err := syscall.UTF16PtrFromString(path)
		if err != nil {
			return nil, err
		}
		fileHandle, err := syscall.CreateFile(wide, syscall.GENERIC_READ, syscall.FILE_SHARE_READ, nil, syscall.OPEN_EXISTING, syscall.FILE_ATTRIBUTE_NORMAL, 0)
		if err != nil {
			return nil, err
		}
		f := os.NewFile(uintptr(fileHandle), path)
		pins = append(pins, f)
		h := sha256.New()
		n, readErr := io.Copy(h, io.LimitReader(f, pin.bytes+1))
		if readErr != nil {
			return nil, readErr
		}
		if n != pin.bytes || hex.EncodeToString(h.Sum(nil)) != pin.sha {
			return nil, fmt.Errorf("pinned dependency mismatch: %s", pin.name)
		}
	}
	fullDLL := filepath.Join(directory, "WinDivert.dll")
	wideDLL, err := syscall.UTF16PtrFromString(fullDLL)
	if err != nil {
		return nil, err
	}
	load := syscall.NewLazyDLL("kernel32.dll").NewProc("LoadLibraryExW")
	module, _, loadErr := load.Call(uintptr(unsafe.Pointer(wideDLL)), 0, 0x800) // Dependencies: SYSTEM32 only; no global DLL search changes.
	runtime.KeepAlive(wideDLL)
	if module == 0 {
		return nil, fmt.Errorf("LoadLibraryExW pinned WinDivert: %w", loadErr)
	}
	dll = &syscall.DLL{Name: fullDLL, Handle: syscall.Handle(module)}
	procs := make(map[string]*syscall.Proc)
	for _, name := range []string{"WinDivertOpen", "WinDivertRecv", "WinDivertSend", "WinDivertShutdown", "WinDivertClose", "WinDivertSetParam", "WinDivertGetParam"} {
		proc, e := dll.FindProc(name)
		if e != nil {
			return nil, e
		}
		procs[name] = proc
	}
	filter, err := syscall.BytePtrFromString(plan.Filter)
	if err != nil {
		return nil, err
	}
	flags := uintptr(0)
	if mode == Inspect {
		flags = 0x5
	} // SNIFF | RECV_ONLY, never RECV_ONLY alone.
	handle, _, openErr := procs["WinDivertOpen"].Call(uintptr(unsafe.Pointer(filter)), 1, 0, flags)
	runtime.KeepAlive(filter)
	if handle == 0 || handle == ^uintptr(0) {
		return nil, fmt.Errorf("WinDivertOpen NETWORK_FORWARD: %w", openErr)
	}
	link := &nativeLink{mode: mode, handle: handle, dll: dll, pins: pins, recv: procs["WinDivertRecv"], send: procs["WinDivertSend"], shutdown: procs["WinDivertShutdown"], closeHandle: procs["WinDivertClose"], setParam: procs["WinDivertSetParam"], getParam: procs["WinDivertGetParam"]}
	handedOff = true
	// If any setup fails after Open, return the owned link with the error. Run
	// still shuts down, drains and closes it; never abandon an active filter.
	for _, parameter := range []struct {
		id    uintptr
		value uint64
	}{{0, 4096}, {1, 2000}, {2, 4194304}} {
		ok, _, e := link.setParam.Call(handle, parameter.id, uintptr(parameter.value))
		if ok == 0 {
			return link, fmt.Errorf("WinDivertSetParam(%d): %w", parameter.id, e)
		}
		value, e := link.readParam(parameter.id)
		if e != nil {
			return link, e
		}
		if value != parameter.value {
			return link, fmt.Errorf("queue parameter%d readback mismatch", parameter.id)
		}
	}
	for _, parameter := range []uintptr{3, 4} {
		value, e := link.readParam(parameter)
		if e != nil {
			return link, e
		}
		if value != 2 {
			return link, fmt.Errorf("unsupported WinDivert major/minor parameter%d=%d", parameter, value)
		}
	}
	return link, nil
}

func (l *nativeLink) readParam(parameter uintptr) (uint64, error) {
	var value uint64
	ok, _, err := l.getParam.Call(l.handle, parameter, uintptr(unsafe.Pointer(&value)))
	runtime.KeepAlive(&value)
	if ok == 0 {
		return 0, fmt.Errorf("WinDivertGetParam(%d): %w", parameter, err)
	}
	return value, nil
}
