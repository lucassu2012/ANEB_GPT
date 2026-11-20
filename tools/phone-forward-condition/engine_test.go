package condition

import (
	"bytes"
	"context"
	"encoding/binary"
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
	// This remains an overflow/restoration challenge at the explicit new packet
	// limit. The former 40-packet input is retained as an accepted burst below.
	frames := make([]Frame, maxPendingPackets+2)
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
	if got.r.ReceivedPackets != uint64(len(frames)) || got.r.UnconfirmedUserPackets != 0 || got.r.CleanupInjectedPackets != uint64(len(frames)) || got.r.CleanupInjectedBytes != uint64(len(frames)*100) {
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

func TestBidirectionalWorkersPaceIndependentlyWithoutHeadOfLineBlocking(t *testing.T) {
	down := testForwardFrame(1000)
	up1 := testUplinkForwardFrame(100, 16, true)
	up2 := testUplinkForwardFrame(100, 16, true)
	up3 := testUplinkForwardFrame(100, 16, true)
	link := &memoryLink{frames: []Frame{down, up1, up2, up3}}
	receipt, err := Run(
		context.Background(),
		Config{
			PhoneAddresses:         []string{"192.0.2.8"},
			InterfaceIndex:         12,
			UplinkInterfaceIndex:   16,
			TTL:                    2 * time.Second,
			DownlinkBytesPerSecond: 5000,
			DownlinkDelay:          200 * time.Millisecond, // 200ms serial + 200ms delay = 400ms
			UplinkBytesPerSecond:   1_000_000,
			UplinkDelay:            0,
		},
		Limit,
		SystemClock{},
		func(Plan, Mode) (Link, error) { return link, nil },
	)
	if err != nil {
		t.Fatalf("bidirectional run failed: %v receipt=%+v", err, receipt)
	}
	if receipt.Downlink == nil || receipt.Uplink == nil ||
		receipt.Downlink.InjectedPackets != 1 || receipt.Uplink.InjectedPackets != 3 ||
		receipt.UnconfirmedUserPackets != 0 || receipt.Downlink.UnconfirmedUserPackets != 0 || receipt.Uplink.UnconfirmedUserPackets != 0 {
		t.Fatalf("unexpected directional counts: %+v down=%+v up=%+v", receipt, receipt.Downlink, receipt.Uplink)
	}
	if len(link.injected) != 4 {
		t.Fatalf("expected 4 injected frames, got %d", len(link.injected))
	}
	// All 3 fast uplink frames must inject BEFORE the slow/delayed downlink frame.
	for i := range 3 {
		if link.injected[i].Packet[12] != 192 {
			t.Fatalf("uplink frame %d was head-of-line blocked behind downlink: injected order=%d", i, len(link.injected[i].Packet))
		}
	}
	if link.injected[3].Packet[16] != 192 {
		t.Fatal("last injected frame was not the delayed downlink frame")
	}
}

func TestBidirectionalQueueOverflowRestoresBothChannelsWithoutDeadlock(t *testing.T) {
	clock := &heldClock{now: time.Unix(0, 0), entered: make(chan struct{}, 2), release: make(chan struct{})}
	frames := make([]Frame, 0, maxPendingPackets+7)
	for range maxPendingPackets + 2 {
		frames = append(frames, testForwardFrame(100))
	}
	for range 5 {
		frames = append(frames, testUplinkForwardFrame(80, 16, true))
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
		r, e := Run(
			ctx,
			Config{
				PhoneAddresses:         []string{"192.0.2.8"},
				InterfaceIndex:         12,
				UplinkInterfaceIndex:   16,
				TTL:                    4 * time.Second,
				DownlinkBytesPerSecond: 100,
				UplinkBytesPerSecond:   100,
			},
			Limit,
			clock,
			func(Plan, Mode) (Link, error) { return link, nil },
		)
		done <- outcome{r, e}
	}()
	var got outcome
	select {
	case got = <-done:
	case <-time.After(1 * time.Second):
		cancel()
		got = <-done
		t.Fatal("bidirectional overflow deadlocked during drain")
	}
	if !errors.Is(got.e, ErrQueueOverflow) || got.r.StopReason != "USER_QUEUE_OVERFLOW" {
		t.Fatalf("expected USER_QUEUE_OVERFLOW, got err=%v receipt=%+v", got.e, got.r)
	}
	if got.r.Downlink == nil || got.r.Uplink == nil ||
		got.r.UnconfirmedUserPackets != 0 || got.r.Downlink.UnconfirmedUserPackets != 0 || got.r.Uplink.UnconfirmedUserPackets != 0 ||
		got.r.CleanupInjectedPackets+got.r.InjectedPackets != uint64(len(frames)) {
		t.Fatalf("bidirectional overflow restoration mismatch: %+v down=%+v up=%+v", got.r, got.r.Downlink, got.r.Uplink)
	}
}

func TestBidirectionalTTLRestoresUnpacedDownlinkWhileKeepingPacedUplink(t *testing.T) {
	down := testForwardFrame(20)               // 20B @ 100 B/s = 200ms > 50ms TTL -> held & restored
	up := testUplinkForwardFrame(20, 16, true) // 20B @ 10000 B/s = 2ms < 50ms TTL -> injected
	link := &idleLink{memoryLink: memoryLink{frames: []Frame{up, down}}, stop: make(chan struct{})}
	receipt, err := Run(
		context.Background(),
		Config{
			PhoneAddresses:         []string{"192.0.2.8"},
			InterfaceIndex:         12,
			UplinkInterfaceIndex:   16,
			TTL:                    50 * time.Millisecond,
			DownlinkBytesPerSecond: 100,
			UplinkBytesPerSecond:   10_000,
		},
		Limit,
		SystemClock{},
		func(Plan, Mode) (Link, error) { return link, nil },
	)
	if err != nil || receipt.StopReason != "TTL_EXPIRED" {
		t.Fatalf("unexpected bidirectional TTL expiry outcome: %+v err=%v", receipt, err)
	}
	if receipt.Downlink == nil || receipt.Uplink == nil ||
		receipt.Uplink.InjectedPackets != 1 || receipt.Downlink.InjectedPackets != 0 || receipt.Downlink.CleanupInjectedPackets != 1 ||
		receipt.UnconfirmedUserPackets != 0 || receipt.Downlink.UnconfirmedUserPackets != 0 || receipt.Uplink.UnconfirmedUserPackets != 0 {
		t.Fatalf("bidirectional TTL conservation mismatch: %+v down=%+v up=%+v", receipt, receipt.Downlink, receipt.Uplink)
	}
}

func TestBidirectionalRunWithUplinkNatAddresses(t *testing.T) {
	down := testForwardFrame(100)
	copy(down.Packet[16:20], []byte{192, 168, 137, 113})
	binary.LittleEndian.PutUint32(down.Address.Data[0:4], 16)
	up := testUplinkForwardFrame(100, 12, true)
	copy(up.Packet[12:16], []byte{10, 10, 8, 9})
	link := &memoryLink{frames: []Frame{down, up}}
	receipt, err := Run(
		context.Background(),
		Config{
			PhoneAddresses:         []string{"192.168.137.113"},
			InterfaceIndex:         16,
			UplinkInterfaceIndex:   12,
			TTL:                    2 * time.Second,
			DownlinkBytesPerSecond: 250000,
			UplinkBytesPerSecond:   62500,
			UplinkNatAddresses:     []string{"10.10.8.9"},
		},
		Limit,
		SystemClock{},
		func(Plan, Mode) (Link, error) { return link, nil },
	)
	if err != nil {
		t.Fatalf("NAT bidirectional run failed: %v receipt=%+v", err, receipt)
	}
	if receipt.Downlink == nil || receipt.Uplink == nil ||
		receipt.Downlink.InjectedPackets != 1 || receipt.Uplink.InjectedPackets != 1 ||
		receipt.UnconfirmedUserPackets != 0 {
		t.Fatalf("unexpected NAT receipt counts: %+v down=%+v up=%+v", receipt, receipt.Downlink, receipt.Uplink)
	}
}

type stagedLink struct {
	mu       sync.Mutex
	in       chan Frame
	stop     chan struct{}
	once     sync.Once
	injected []Frame
	onInject func(Frame)
}

func (l *stagedLink) Receive() (Frame, error) {
	select {
	case f := <-l.in:
		return f, nil
	case <-l.stop:
		return Frame{}, io.EOF
	}
}

func (l *stagedLink) Inject(f Frame) (int, error) {
	l.mu.Lock()
	l.injected = append(l.injected, Frame{Packet: append([]byte(nil), f.Packet...), Address: f.Address})
	cb := l.onInject
	l.mu.Unlock()
	if cb != nil {
		cb(f)
	}
	return len(f.Packet), nil
}

func (l *stagedLink) ShutdownReceive() error {
	l.once.Do(func() { close(l.stop) })
	return nil
}

func (l *stagedLink) Close() error { return nil }

type syncManualClock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *syncManualClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *syncManualClock) WaitUntil(ctx context.Context, due time.Time) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	c.mu.Lock()
	if due.After(c.now) {
		c.now = due
	}
	c.mu.Unlock()
	return nil
}

func TestScopeMismatchNotSwallowedBehindErrTTLDownlink(t *testing.T) {
	firstInjected := make(chan struct{}, 1)
	link := &stagedLink{
		in:   make(chan Frame, 8),
		stop: make(chan struct{}),
		onInject: func(Frame) {
			select {
			case firstInjected <- struct{}{}:
			default:
			}
		},
	}
	clock := &syncManualClock{now: time.Unix(0, 0)}

	dl100 := testForwardFrame(100)
	binary.LittleEndian.PutUint32(dl100.Address.Data[0:4], 16)
	dl1000 := testForwardFrame(1000)
	binary.LittleEndian.PutUint32(dl1000.Address.Data[0:4], 16)
	badIf := testForwardFrame(100)
	binary.LittleEndian.PutUint32(badIf.Address.Data[0:4], 99)

	type outcome struct {
		receipt Receipt
		err     error
	}
	done := make(chan outcome, 1)
	go func() {
		r, err := Run(
			context.Background(),
			Config{
				PhoneAddresses:         []string{"192.0.2.8"},
				InterfaceIndex:         16,
				UplinkInterfaceIndex:   12,
				TTL:                    300 * time.Millisecond,
				DownlinkBytesPerSecond: 1000,
				UplinkBytesPerSecond:   100_000,
			},
			Limit,
			clock,
			func(Plan, Mode) (Link, error) { return link, nil },
		)
		done <- outcome{r, err}
	}()

	link.in <- dl100
	select {
	case <-firstInjected:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for first downlink injection")
	}
	link.in <- dl1000
	time.Sleep(25 * time.Millisecond)
	link.in <- badIf

	got := <-done
	if got.err == nil || got.receipt.State != "INVALID" || got.receipt.StopReason != "PACKET_SCOPE_MISMATCH" {
		t.Fatalf("scope mismatch swallowed behind ErrTTL: err=%v receipt=%+v", got.err, got.receipt)
	}
	if got.receipt.InjectedPackets != 1 || got.receipt.CleanupInjectedPackets != 2 ||
		got.receipt.UnconfirmedUserPackets != 0 ||
		got.receipt.Downlink == nil || got.receipt.Uplink == nil ||
		got.receipt.Downlink.UnconfirmedUserPackets != 0 || got.receipt.Uplink.UnconfirmedUserPackets != 0 ||
		!got.receipt.DriverDrained || !got.receipt.OwnedHandleClosed || !got.receipt.WorkersJoined {
		t.Fatalf("unexpected conservation/cleanup state after ErrTTL scope mismatch: %+v down=%+v up=%+v", got.receipt, got.receipt.Downlink, got.receipt.Uplink)
	}
}

func TestScopeMismatchNotSwallowedBehindSlowDownlinkQueue(t *testing.T) {
	clock := &heldClock{now: time.Unix(0, 0), entered: make(chan struct{}, 2), release: make(chan struct{})}
	link := &stagedLink{
		in:   make(chan Frame, 8),
		stop: make(chan struct{}),
	}

	dl1 := testForwardFrame(100)
	binary.LittleEndian.PutUint32(dl1.Address.Data[0:4], 16)
	dl2 := testForwardFrame(100)
	binary.LittleEndian.PutUint32(dl2.Address.Data[0:4], 16)
	badIf := testForwardFrame(100)
	binary.LittleEndian.PutUint32(badIf.Address.Data[0:4], 99)

	type outcome struct {
		receipt Receipt
		err     error
	}
	done := make(chan outcome, 1)
	go func() {
		r, err := Run(
			context.Background(),
			Config{
				PhoneAddresses:         []string{"192.0.2.8"},
				InterfaceIndex:         16,
				UplinkInterfaceIndex:   12,
				TTL:                    250 * time.Millisecond,
				DownlinkBytesPerSecond: 1000,
				DownlinkDelay:          100 * time.Millisecond,
				UplinkBytesPerSecond:   100_000,
			},
			Limit,
			clock,
			func(Plan, Mode) (Link, error) { return link, nil },
		)
		done <- outcome{r, err}
	}()

	link.in <- dl1
	select {
	case <-clock.entered:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for slow downlink pacer entry")
	}
	link.in <- dl2
	link.in <- badIf

	got := <-done
	if got.err == nil || got.receipt.State != "INVALID" || got.receipt.StopReason != "PACKET_SCOPE_MISMATCH" {
		t.Fatalf("scope mismatch swallowed behind slow downlink queue: err=%v receipt=%+v", got.err, got.receipt)
	}
	if got.receipt.InjectedPackets != 0 || got.receipt.CleanupInjectedPackets != 3 ||
		got.receipt.UnconfirmedUserPackets != 0 ||
		got.receipt.Downlink == nil || got.receipt.Uplink == nil ||
		got.receipt.Downlink.UnconfirmedUserPackets != 0 || got.receipt.Uplink.UnconfirmedUserPackets != 0 ||
		!got.receipt.DriverDrained || !got.receipt.OwnedHandleClosed || !got.receipt.WorkersJoined {
		t.Fatalf("unexpected conservation/cleanup state after slow-queue scope mismatch: %+v down=%+v up=%+v", got.receipt, got.receipt.Downlink, got.receipt.Uplink)
	}
}

type scriptedInjectLink struct {
	idleLink
	injectCalls atomic.Int32
	onInject    func(call int32, f Frame) (int, error)
}

func (l *scriptedInjectLink) Inject(f Frame) (int, error) {
	call := l.injectCalls.Add(1)
	if l.onInject != nil {
		return l.onInject(call, f)
	}
	return l.memoryLink.Inject(f)
}

func TestMidInjectTTLUncertainSendFailsClosedWithUnconfirmedPacket(t *testing.T) {
	dl1 := testForwardFrame(100)
	binary.LittleEndian.PutUint32(dl1.Address.Data[0:4], 16)
	dl2 := testForwardFrame(100)
	binary.LittleEndian.PutUint32(dl2.Address.Data[0:4], 16)
	dl3 := testForwardFrame(100)
	binary.LittleEndian.PutUint32(dl3.Address.Data[0:4], 16)

	link := &scriptedInjectLink{
		idleLink: idleLink{
			memoryLink: memoryLink{frames: []Frame{dl1, dl2, dl3}},
			stop:       make(chan struct{}),
		},
		onInject: func(call int32, f Frame) (int, error) {
			switch call {
			case 2:
				// Stall past TTL=120ms while admitted inside boundedCall,
				// then finish before cleanup restore completes so WorkersJoined==true.
				time.Sleep(180 * time.Millisecond)
			case 3:
				// Cleanup restore of dl3 waits long enough for call #2 to finish.
				time.Sleep(220 * time.Millisecond)
			}
			return len(f.Packet), nil
		},
	}

	receipt, err := Run(
		context.Background(),
		Config{
			PhoneAddresses:         []string{"192.0.2.8"},
			InterfaceIndex:         16,
			UplinkInterfaceIndex:   12,
			TTL:                    120 * time.Millisecond,
			DownlinkBytesPerSecond: 10_000,
			UplinkBytesPerSecond:   100_000,
		},
		Limit,
		SystemClock{},
		func(Plan, Mode) (Link, error) { return link, nil },
	)
	if err == nil || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("mid-Inject TTL uncertain send must fail closed with DeadlineExceeded, got err=%v receipt=%+v", err, receipt)
	}
	if receipt.State != "INVALID" || receipt.StopReason != "INJECT_TIMEOUT" {
		t.Fatalf("expected INVALID/INJECT_TIMEOUT, got state=%q stopReason=%q receipt=%+v", receipt.State, receipt.StopReason, receipt)
	}
	if receipt.InjectedPackets != 1 || receipt.CleanupInjectedPackets != 1 || receipt.UnconfirmedUserPackets != 1 {
		t.Fatalf("expected Injected=1 Cleanup=1 Unconfirmed=1, got %+v", receipt)
	}
	if receipt.Downlink == nil || receipt.Uplink == nil ||
		receipt.Downlink.UnconfirmedUserPackets != 1 || receipt.Uplink.UnconfirmedUserPackets != 0 {
		t.Fatalf("expected Downlink.Unconfirmed=1 Uplink.Unconfirmed=0, got down=%+v up=%+v", receipt.Downlink, receipt.Uplink)
	}
	if !receipt.DriverDrained || !receipt.OwnedHandleClosed || !receipt.WorkersJoined || len(receipt.Failures) == 0 {
		t.Fatalf("unexpected lifecycle/failures state: %+v", receipt)
	}
}

func TestPreInjectErrTTLAfterInjectedPacketRemainsValid(t *testing.T) {
	dl1 := testForwardFrame(100)  // 100B @ 10_000 B/s = 10ms < 120ms TTL -> injected
	dl2 := testForwardFrame(2000) // 2000B @ 10_000 B/s = 200ms > 120ms TTL -> !attempted, restored in cleanup
	binary.LittleEndian.PutUint32(dl1.Address.Data[0:4], 16)
	binary.LittleEndian.PutUint32(dl2.Address.Data[0:4], 16)

	link := &idleLink{
		memoryLink: memoryLink{frames: []Frame{dl1, dl2}},
		stop:       make(chan struct{}),
	}
	receipt, err := Run(
		context.Background(),
		Config{
			PhoneAddresses:         []string{"192.0.2.8"},
			InterfaceIndex:         16,
			UplinkInterfaceIndex:   12,
			TTL:                    120 * time.Millisecond,
			DownlinkBytesPerSecond: 10_000,
			UplinkBytesPerSecond:   100_000,
		},
		Limit,
		SystemClock{},
		func(Plan, Mode) (Link, error) { return link, nil },
	)
	if err != nil {
		t.Fatalf("normal pre-Inject ErrTTL should succeed, got err=%v receipt=%+v", err, receipt)
	}
	if receipt.State != "INJECTED_NOT_CALIBRATED" || receipt.StopReason != "TTL_EXPIRED" {
		t.Fatalf("expected INJECTED_NOT_CALIBRATED/TTL_EXPIRED, got %+v", receipt)
	}
	if receipt.InjectedPackets != 1 || receipt.CleanupInjectedPackets != 1 || receipt.UnconfirmedUserPackets != 0 ||
		receipt.Downlink == nil || receipt.Uplink == nil ||
		receipt.Downlink.UnconfirmedUserPackets != 0 || receipt.Uplink.UnconfirmedUserPackets != 0 {
		t.Fatalf("unexpected pre-Inject ErrTTL counts: %+v down=%+v up=%+v", receipt, receipt.Downlink, receipt.Uplink)
	}
}

func TestAdmittedInjectDriverErrorFailsClosedWithUnconfirmedPacket(t *testing.T) {
	dl1 := testForwardFrame(100)
	injectErr := errors.New("driver inject failed")
	link := &scriptedInjectLink{
		idleLink: idleLink{
			memoryLink: memoryLink{frames: []Frame{dl1}},
			stop:       make(chan struct{}),
		},
		onInject: func(int32, Frame) (int, error) {
			return 0, injectErr
		},
	}
	receipt, err := Run(
		context.Background(),
		Config{
			PhoneAddresses:         []string{"192.0.2.8"},
			InterfaceIndex:         12,
			TTL:                    time.Second,
			DownlinkBytesPerSecond: 1_000_000,
		},
		Limit,
		SystemClock{},
		func(Plan, Mode) (Link, error) { return link, nil },
	)
	if !errors.Is(err, injectErr) || receipt.State != "INVALID" || receipt.StopReason != "INJECT_ERROR" || receipt.UnconfirmedUserPackets != 1 {
		t.Fatalf("expected INVALID/INJECT_ERROR with Unconfirmed=1, got err=%v receipt=%+v", err, receipt)
	}
}

func (c *syncManualClock) Advance(d time.Duration) {
	c.mu.Lock()
	c.now = c.now.Add(d)
	c.mu.Unlock()
}

func TestPostInjectErrTTLAfterConfirmedSendRemainsValid(t *testing.T) {
	dl1 := testForwardFrame(90) // 90B @ 1000 B/s = 90ms < 100ms TTL
	clock := &syncManualClock{now: time.Unix(0, 0)}
	link := &stagedLink{
		in:   make(chan Frame, 4),
		stop: make(chan struct{}),
		onInject: func(Frame) {
			// Simulate Inject completing just past the 100ms TTL after confirmed send.
			clock.Advance(15 * time.Millisecond)
		},
	}
	link.in <- dl1

	receipt, err := Run(
		context.Background(),
		Config{
			PhoneAddresses:         []string{"192.0.2.8"},
			InterfaceIndex:         12,
			TTL:                    100 * time.Millisecond,
			DownlinkBytesPerSecond: 1000,
		},
		Limit,
		clock,
		func(Plan, Mode) (Link, error) { return link, nil },
	)
	if err != nil {
		t.Fatalf("confirmed post-Inject ErrTTL must not fail run: err=%v receipt=%+v", err, receipt)
	}
	if receipt.State != "INJECTED_NOT_CALIBRATED" || receipt.StopReason != "TTL_EXPIRED" {
		t.Fatalf("expected INJECTED_NOT_CALIBRATED/TTL_EXPIRED, got state=%q stopReason=%q receipt=%+v", receipt.State, receipt.StopReason, receipt)
	}
	if receipt.InjectedPackets != 1 || receipt.CleanupInjectedPackets != 0 || receipt.UnconfirmedUserPackets != 0 ||
		!receipt.DriverDrained || !receipt.OwnedHandleClosed || !receipt.WorkersJoined || len(receipt.Failures) != 0 {
		t.Fatalf("unexpected post-Inject ErrTTL receipt: %+v", receipt)
	}
}

func TestBidirectionalPostInjectErrTTLDoesNotCancelConcurrentDirectionOrMaskScopeMismatch(t *testing.T) {
	t.Run("concurrent_uplink_finishes_cleanly", func(t *testing.T) {
		dl1 := testForwardFrame(90) // 90B @ 1000 B/s = 90ms < 120ms TTL
		binary.LittleEndian.PutUint32(dl1.Address.Data[0:4], 16)
		up1 := testUplinkForwardFrame(50, 12, true) // 50B @ 10_000 B/s = 5ms
		up2 := testUplinkForwardFrame(50, 12, true) // queued/held after DL crosses TTL -> restored in cleanup

		clock := &syncManualClock{now: time.Unix(0, 0)}
		upEntered := make(chan struct{})
		dlConfirmed := make(chan struct{})
		up1Done := make(chan struct{})
		var dlOnce, upOnce sync.Once

		link := &stagedLink{
			in:   make(chan Frame, 8),
			stop: make(chan struct{}),
			onInject: func(f Frame) {
				if f.Packet[16] == 192 { // downlink frame
					dlOnce.Do(func() {
						// Cross 120ms TTL while confirmed send completes.
						clock.Advance(40 * time.Millisecond)
						close(dlConfirmed)
					})
				} else { // uplink frame
					upOnce.Do(func() {
						close(upEntered)
						// Wait until DL has already hit post-Inject ErrTTL; if DL wrongly
						// called cancel(ErrTTL), ctx would be cancelled before UL finishes.
						<-dlConfirmed
						time.Sleep(20 * time.Millisecond)
						close(up1Done)
					})
				}
			},
		}

		type outcome struct {
			receipt Receipt
			err     error
		}
		done := make(chan outcome, 1)
		go func() {
			r, err := Run(
				context.Background(),
				Config{
					PhoneAddresses:         []string{"192.0.2.8"},
					InterfaceIndex:         16,
					UplinkInterfaceIndex:   12,
					TTL:                    120 * time.Millisecond,
					DownlinkBytesPerSecond: 1000,
					UplinkBytesPerSecond:   10_000,
				},
				Limit,
				clock,
				func(Plan, Mode) (Link, error) { return link, nil },
			)
			done <- outcome{r, err}
		}()

		link.in <- up1
		<-upEntered
		link.in <- dl1
		<-up1Done
		link.in <- up2

		got := <-done
		if got.err != nil {
			t.Fatalf("bidirectional post-Inject ErrTTL failed: err=%v receipt=%+v", got.err, got.receipt)
		}
		if got.receipt.State != "INJECTED_NOT_CALIBRATED" || got.receipt.StopReason != "TTL_EXPIRED" {
			t.Fatalf("expected INJECTED_NOT_CALIBRATED/TTL_EXPIRED, got %+v", got.receipt)
		}
		if got.receipt.Downlink == nil || got.receipt.Uplink == nil ||
			got.receipt.Downlink.InjectedPackets != 1 || got.receipt.Downlink.UnconfirmedUserPackets != 0 ||
			got.receipt.Uplink.InjectedPackets != 1 || got.receipt.Uplink.CleanupInjectedPackets != 1 || got.receipt.Uplink.UnconfirmedUserPackets != 0 ||
			got.receipt.UnconfirmedUserPackets != 0 || !got.receipt.DriverDrained || !got.receipt.OwnedHandleClosed || !got.receipt.WorkersJoined {
			t.Fatalf("unexpected bidirectional conservation state: %+v down=%+v up=%+v", got.receipt, got.receipt.Downlink, got.receipt.Uplink)
		}
	})

	t.Run("preserves_scope_mismatch_after_post_inject_errttl", func(t *testing.T) {
		dl1 := testForwardFrame(90)
		binary.LittleEndian.PutUint32(dl1.Address.Data[0:4], 16)
		badIf := testForwardFrame(100)
		binary.LittleEndian.PutUint32(badIf.Address.Data[0:4], 99)

		clock := &syncManualClock{now: time.Unix(0, 0)}
		dlConfirmed := make(chan struct{})
		var dlOnce sync.Once

		link := &stagedLink{
			in:   make(chan Frame, 4),
			stop: make(chan struct{}),
			onInject: func(f Frame) {
				dlOnce.Do(func() {
					clock.Advance(120 * time.Millisecond)
					close(dlConfirmed)
				})
			},
		}

		type outcome struct {
			receipt Receipt
			err     error
		}
		done := make(chan outcome, 1)
		go func() {
			r, err := Run(
				context.Background(),
				Config{
					PhoneAddresses:         []string{"192.0.2.8"},
					InterfaceIndex:         16,
					UplinkInterfaceIndex:   12,
					TTL:                    200 * time.Millisecond,
					DownlinkBytesPerSecond: 1000,
					UplinkBytesPerSecond:   100_000,
				},
				Limit,
				clock,
				func(Plan, Mode) (Link, error) { return link, nil },
			)
			done <- outcome{r, err}
		}()

		link.in <- dl1
		<-dlConfirmed
		link.in <- badIf

		got := <-done
		if got.err == nil || got.receipt.State != "INVALID" || got.receipt.StopReason != "PACKET_SCOPE_MISMATCH" {
			t.Fatalf("scope mismatch masked after post-Inject ErrTTL: err=%v receipt=%+v", got.err, got.receipt)
		}
		if got.receipt.InjectedPackets != 1 || got.receipt.CleanupInjectedPackets != 1 || got.receipt.UnconfirmedUserPackets != 0 {
			t.Fatalf("unexpected conservation state on post-Inject ErrTTL + scope mismatch: %+v", got.receipt)
		}
	})
}
