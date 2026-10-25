package condition

import (
	"bytes"
	"context"
	"errors"
	"io"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type memoryLink struct {
	mu       sync.Mutex
	frames   []Frame
	injected []Frame
	calls    []string
}

type idleLink struct {
	memoryLink
	stop chan struct{}
	once sync.Once
}

func (l *idleLink) Receive() (Frame, error) {
	frame, err := l.memoryLink.Receive()
	if err == nil {
		return frame, nil
	}
	<-l.stop
	return Frame{}, io.EOF
}

func (l *idleLink) ShutdownReceive() error {
	l.once.Do(func() { close(l.stop) })
	return l.memoryLink.ShutdownReceive()
}

func TestPlannedInspectExpiryWithNoTrafficIsNotAConditionFailure(t *testing.T) {
	link := &idleLink{stop: make(chan struct{})}
	receipt, err := Run(context.Background(), Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: 50 * time.Millisecond, DownlinkBytesPerSecond: 1000}, Inspect, SystemClock{}, func(Plan, Mode) (Link, error) { return link, nil })
	if err != nil || receipt.StopReason != "TTL_EXPIRED" || receipt.State != "NO_MATCHING_PACKETS" || receipt.ReceivedPackets != 0 || !receipt.DriverDrained || !receipt.OwnedHandleClosed || !receipt.WorkersJoined {
		t.Fatalf("normal planned expiry mislabeled: %+v err=%v", receipt, err)
	}
}

type drainOnlyLink struct{ idleLink }

func (l *drainOnlyLink) Receive() (Frame, error) {
	<-l.stop
	return l.memoryLink.Receive()
}

func TestInspectionClassifiesTheFinalCountIncludingShutdownDrain(t *testing.T) {
	frame := testForwardFrame(20)
	frame.Address.Word |= 0x10000
	link := &drainOnlyLink{idleLink: idleLink{memoryLink: memoryLink{frames: []Frame{frame}}, stop: make(chan struct{})}}
	receipt, err := Run(context.Background(), Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: 50 * time.Millisecond, DownlinkBytesPerSecond: 1000}, Inspect, SystemClock{}, func(Plan, Mode) (Link, error) { return link, nil })
	if err != nil || receipt.State != "OBSERVED_NOT_QUALIFIED" || receipt.ReceivedPackets != 1 || !receipt.DriverDrained || !receipt.WorkersJoined {
		t.Fatalf("drained copy contradicts final observation classification: %+v err=%v", receipt, err)
	}
}

func TestPacketThatCannotFitWaitsForPlannedEndAndIsRestoredOnlyInCleanup(t *testing.T) {
	frame := testForwardFrame(20)
	link := &idleLink{memoryLink: memoryLink{frames: []Frame{frame}}, stop: make(chan struct{})}
	started := time.Now()
	receipt, err := Run(context.Background(), Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: 50 * time.Millisecond, DownlinkBytesPerSecond: 100}, Limit, SystemClock{}, func(Plan, Mode) (Link, error) { return link, nil })
	if err != nil || receipt.StopReason != "TTL_EXPIRED" || receipt.State != "NO_PACED_PACKETS" || receipt.InjectedPackets != 0 || receipt.CleanupInjectedPackets != 1 || receipt.UnconfirmedUserPackets != 0 || !receipt.DriverDrained || !receipt.OwnedHandleClosed || !receipt.WorkersJoined || time.Since(started) < 50*time.Millisecond {
		t.Fatalf("planned cutoff lost packet or ended early: %+v err=%v elapsed=%v", receipt, err, time.Since(started))
	}
	if len(link.injected) != 1 || !bytes.Equal(link.injected[0].Packet, frame.Packet) || link.injected[0].Address != frame.Address {
		t.Fatal("cleanup did not restore the unchanged original")
	}
}

type failedShutdownLink struct{ *idleLink }

func (l *failedShutdownLink) ShutdownReceive() error {
	_ = l.idleLink.ShutdownReceive()
	return errors.New("shutdown confirmation failed")
}

func TestParentDeadlineAndCleanupErrorsAreNotNormalizedAsPlannedExpiry(t *testing.T) {
	for _, cleanupFailure := range []bool{false, true} {
		t.Run(map[bool]string{false: "parent-deadline", true: "cleanup-failure"}[cleanupFailure], func(t *testing.T) {
			idle := &idleLink{stop: make(chan struct{})}
			var link Link = idle
			ctx := context.Background()
			if cleanupFailure {
				link = &failedShutdownLink{idleLink: idle}
			} else {
				var cancel context.CancelFunc
				ctx, cancel = context.WithTimeout(ctx, 10*time.Millisecond)
				defer cancel()
			}
			receipt, err := Run(ctx, Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: 50 * time.Millisecond, DownlinkBytesPerSecond: 1000}, Inspect, SystemClock{}, func(Plan, Mode) (Link, error) { return link, nil })
			if err == nil || receipt.State != "INVALID" || len(receipt.Failures) == 0 {
				t.Fatalf("real failure normalized: %+v err=%v", receipt, err)
			}
			if !cleanupFailure && !errors.Is(err, context.DeadlineExceeded) {
				t.Fatalf("parent deadline lost: %v", err)
			}
		})
	}
}

type burstLink struct {
	memoryLink
	received atomic.Int32
	all      chan struct{}
}

func (l *burstLink) Receive() (Frame, error) {
	f, err := l.memoryLink.Receive()
	if err == nil && l.received.Add(1) == 3 {
		close(l.all)
	}
	return f, err
}

func TestReceiverKeepsDrainingWhileFirstPacketIsPaced(t *testing.T) {
	clock := &heldClock{now: time.Unix(0, 0), entered: make(chan struct{}, 4), release: make(chan struct{})}
	frame := testForwardFrame(100)
	link := &burstLink{memoryLink: memoryLink{frames: []Frame{frame, frame, frame}}, all: make(chan struct{})}
	result := make(chan error, 1)
	go func() {
		_, err := Run(context.Background(), Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: 4 * time.Second, DownlinkBytesPerSecond: 100}, Limit, clock, func(Plan, Mode) (Link, error) { return link, nil })
		result <- err
	}()
	<-clock.entered
	select {
	case <-link.all:
	case <-time.After(200 * time.Millisecond):
		t.Error("receiver blocked behind first packet pacing")
	}
	close(clock.release)
	if err := <-result; err != nil {
		t.Fatal(err)
	}
}

type restoringLink struct {
	burstLink
	t *testing.T
}

func (l *restoringLink) Inject(f Frame) (int, error) {
	l.mu.Lock()
	stopped := len(l.calls) > 0 && l.calls[0] == "shutdown-receive"
	l.mu.Unlock()
	if !stopped {
		l.t.Error("cleanup injected before stopping new capture")
	}
	return l.memoryLink.Inject(f)
}

func TestCancelledPacingRestoresOwnedPendingFramesAfterReceiveShutdown(t *testing.T) {
	clock := &heldClock{now: time.Unix(0, 0), entered: make(chan struct{}, 1), release: make(chan struct{})}
	frame := testForwardFrame(100)
	frame.Address.TimestampQPC = 987
	link := &restoringLink{burstLink: burstLink{memoryLink: memoryLink{frames: []Frame{frame, frame, frame}}, all: make(chan struct{})}, t: t}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	type outcome struct {
		receipt Receipt
		err     error
	}
	done := make(chan outcome, 1)
	go func() {
		r, err := Run(ctx, Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: 4 * time.Second, DownlinkBytesPerSecond: 100}, Limit, clock, func(Plan, Mode) (Link, error) { return link, nil })
		done <- outcome{r, err}
	}()
	<-clock.entered
	<-link.all
	cancel()
	got := <-done
	if !errors.Is(got.err, context.Canceled) {
		t.Fatal(got.err)
	}
	if got.receipt.CleanupInjectedPackets != 3 || got.receipt.CleanupInjectedBytes != 300 || got.receipt.UnconfirmedUserPackets != 0 || !got.receipt.DriverDrained || !got.receipt.OwnedHandleClosed || !got.receipt.WorkersJoined {
		t.Fatalf("owned frames lost in cancellation: %+v", got.receipt)
	}
	if got.receipt.InjectedPackets != 0 {
		t.Fatal("cleanup mislabeled as paced experiment traffic")
	}
	for _, sent := range link.injected {
		if !bytes.Equal(sent.Packet, frame.Packet) || sent.Address != frame.Address {
			t.Fatal("cleanup changed captured frame")
		}
	}
}

func TestInspectNeverInjectsCopiesAndDoesNotClaimPhoneDelivery(t *testing.T) {
	clock := &manualClock{now: time.Unix(0, 0)}
	frame := testForwardFrame(20)
	frame.Address.Word |= 0x10000
	link := &memoryLink{frames: []Frame{frame}}
	receipt, err := Run(context.Background(), Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 100}, Inspect, clock, func(Plan, Mode) (Link, error) { return link, nil })
	if err != nil {
		t.Fatal(err)
	}
	if len(link.injected) != 0 || receipt.InjectedPackets != 0 || receipt.CleanupInjectedPackets != 0 || receipt.State != "OBSERVED_NOT_QUALIFIED" || receipt.PhoneReceivedBytes != nil || receipt.DriverDroppedPackets != nil {
		t.Fatalf("sniff copy injected or promoted to qualification: %+v", receipt)
	}
}

func TestQueueOverflowInvalidatesConditionInsteadOfSilentlyBackpressuringDriver(t *testing.T) {
	clock := &heldClock{now: time.Unix(0, 0), entered: make(chan struct{}, 1), release: make(chan struct{})}
	frames := make([]Frame, 40)
	for i := range frames {
		frames[i] = testForwardFrame(100)
	}
	link := &memoryLink{frames: frames}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	type outcome struct {
		r Receipt
		e error
	}
	done := make(chan outcome, 1)
	go func() {
		r, e := Run(ctx, Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: 4 * time.Second, DownlinkBytesPerSecond: 100}, Limit, clock, func(Plan, Mode) (Link, error) { return link, nil })
		done <- outcome{r, e}
	}()
	var got outcome
	select {
	case got = <-done:
	case <-time.After(250 * time.Millisecond):
		t.Error("full owned queue did not stop capture/pacing")
		cancel()
		got = <-done
	}
	if got.e == nil || got.r.StopReason != "USER_QUEUE_OVERFLOW" || got.r.State != "INVALID" {
		t.Fatalf("overflow hidden: %+v err%v", got.r, got.e)
	}
	if got.r.ReceivedPackets != 40 || got.r.UnconfirmedUserPackets != 0 || got.r.CleanupInjectedPackets != 40 {
		t.Fatalf("pending originals not restored: %+v", got.r)
	}
}

func TestUnexpectedDestinationIsNotPacedAndOriginalIsRestoredUnchanged(t *testing.T) {
	frame := testForwardFrame(100)
	frame.Packet[19] = 9
	link := &memoryLink{frames: []Frame{frame}}
	receipt, err := Run(context.Background(), Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000}, Limit, &manualClock{now: time.Unix(0, 0)}, func(Plan, Mode) (Link, error) { return link, nil })
	if err == nil || receipt.StopReason != "PACKET_SCOPE_MISMATCH" || receipt.InjectedPackets != 0 || receipt.CleanupInjectedPackets != 1 || len(link.injected) != 1 || !bytes.Equal(link.injected[0].Packet, frame.Packet) {
		t.Fatalf("unexpected packet shaped or not restored: %+v err%v", receipt, err)
	}
}

type cancelAtActualClock struct {
	manualClock
	cancel context.CancelFunc
	waited bool
}

func (c *cancelAtActualClock) Now() time.Time {
	if c.waited {
		c.cancel()
	}
	return c.manualClock.Now()
}
func (c *cancelAtActualClock) WaitUntil(ctx context.Context, due time.Time) error {
	err := c.manualClock.WaitUntil(ctx, due)
	c.waited = true
	return err
}

func TestCancellationBeforeSendDispatchRestoresPacketWithoutCallingItUnknown(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	clock := &cancelAtActualClock{manualClock: manualClock{now: time.Unix(0, 0)}, cancel: cancel}
	link := &memoryLink{frames: []Frame{testForwardFrame(100)}}
	receipt, err := Run(ctx, Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000}, Limit, clock, func(Plan, Mode) (Link, error) { return link, nil })
	if !errors.Is(err, context.Canceled) || receipt.InjectedPackets != 0 || receipt.CleanupInjectedPackets != 1 || receipt.UnconfirmedUserPackets != 0 {
		t.Fatalf("not-dispatched packet was lost: %+v err%v", receipt, err)
	}
}

func TestInspectWithUnexpectedOwnedPacketCannotClaimZeroUnconfirmed(t *testing.T) {
	link := &memoryLink{frames: []Frame{testForwardFrame(20)}}
	receipt, err := Run(context.Background(), Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000}, Inspect, &manualClock{now: time.Unix(0, 0)}, func(Plan, Mode) (Link, error) { return link, nil })
	if err == nil || receipt.State != "INVALID" || receipt.UnconfirmedUserPackets != 1 || len(link.injected) != 0 {
		t.Fatalf("unexpected blocking capture lost from receipt: %+v err%v", receipt, err)
	}
}

func (l *memoryLink) Receive() (Frame, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.frames) == 0 {
		return Frame{}, io.EOF
	}
	f := l.frames[0]
	l.frames = l.frames[1:]
	return f, nil
}
func (l *memoryLink) Inject(f Frame) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.injected = append(l.injected, Frame{Packet: append([]byte(nil), f.Packet...), Address: f.Address})
	return len(f.Packet), nil
}
func (l *memoryLink) ShutdownReceive() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.calls = append(l.calls, "shutdown-receive")
	return nil
}
func (l *memoryLink) Close() error {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.calls = append(l.calls, "close")
	return nil
}

func TestOpenSetupFailureStillDrainsAndClosesReturnedOwnedLink(t *testing.T) {
	link := &memoryLink{frames: []Frame{testForwardFrame(20)}}
	want := errors.New("queue configuration failed after handle open")
	receipt, err := Run(context.Background(), Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000}, Limit, &manualClock{now: time.Unix(0, 0)}, func(Plan, Mode) (Link, error) { return link, want })
	if !errors.Is(err, want) || receipt.StopReason != "OPEN_SETUP_FAILED" || receipt.InjectedPackets != 0 || receipt.CleanupInjectedPackets != 1 || !receipt.OwnedHandleClosed || !receipt.WorkersJoined {
		t.Fatalf("owned link abandoned after setup failure: %+v err%v", receipt, err)
	}
}

type unresponsiveReceiveLink struct {
	started  chan struct{}
	release  chan struct{}
	returned chan struct{}
	closes   atomic.Int32
}

func (l *unresponsiveReceiveLink) Receive() (Frame, error) {
	close(l.started)
	<-l.release
	close(l.returned)
	return Frame{}, io.EOF
}
func (l *unresponsiveReceiveLink) Inject(Frame) (int, error) {
	return 0, errors.New("unexpected injection")
}
func (l *unresponsiveReceiveLink) ShutdownReceive() error { return nil }
func (l *unresponsiveReceiveLink) Close() error           { l.closes.Add(1); return nil }

func TestUnresponsiveReceiverCannotBeReportedAsJoinedOrDrained(t *testing.T) {
	link := &unresponsiveReceiveLink{started: make(chan struct{}), release: make(chan struct{}), returned: make(chan struct{})}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	type outcome struct {
		r Receipt
		e error
	}
	done := make(chan outcome, 1)
	go func() {
		r, e := Run(ctx, Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000}, Inspect, SystemClock{}, func(Plan, Mode) (Link, error) { return link, nil })
		done <- outcome{r, e}
	}()
	<-link.started
	start := time.Now()
	cancel()
	got := <-done
	close(link.release)
	<-link.returned
	if got.e == nil || got.r.WorkersJoined || got.r.DriverDrained || got.r.State != "INVALID" || link.closes.Load() != 1 || time.Since(start) > 4*time.Second {
		t.Fatalf("unresponsive system boundary washed clean: %+v err%v", got.r, got.e)
	}
}

func TestForwardRunPacesOriginalFramesAndClosesOnlyItsLink(t *testing.T) {
	clock := &manualClock{now: time.Unix(0, 0)}
	first := testForwardFrame(20)
	second := testForwardFrame(40)
	a := first.Address
	link := &memoryLink{frames: []Frame{first, second}}
	config := Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000}
	receipt, err := Run(context.Background(), config, Limit, clock, func(plan Plan, mode Mode) (Link, error) {
		if plan.Config.InterfaceIndex != 12 || mode != Limit {
			t.Error("wrong open scope")
		}
		return link, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if receipt.State != "INJECTED_NOT_CALIBRATED" || receipt.InjectedPackets != 2 || receipt.InjectedBytes != 60 || !receipt.DriverDrained || !receipt.OwnedHandleClosed || !receipt.WorkersJoined {
		t.Fatalf("incomplete forwarding receipt: %+v", receipt)
	}
	if receipt.PhoneReceivedBytes != nil || receipt.DriverDroppedPackets != nil {
		t.Fatal("injection/queue config claimed actual phone receipt or zero loss")
	}
	if len(link.injected) != 2 || !bytes.Equal(link.injected[0].Packet, first.Packet) || !bytes.Equal(link.injected[1].Packet, second.Packet) || link.injected[0].Address != a || link.injected[1].Address != a {
		t.Fatal("packet/address not preserved")
	}
	if len(link.calls) != 2 || link.calls[0] != "shutdown-receive" || link.calls[1] != "close" {
		t.Fatalf("unsafe close sequence: %v", link.calls)
	}
	if clock.Now().Sub(time.Unix(0, 0)) != 60*time.Millisecond {
		t.Fatal("pacer not on forwarding path")
	}
}
