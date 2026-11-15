package condition

import (
	"bytes"
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// The OS boundary offers a finite burst only after the first pacing wait.
// This makes the queue challenge deterministic without mocking Run or Pacer.
type gatedBurstLink struct {
	memoryLink
	received        atomic.Int32
	gate            chan struct{}
	gateOnce        sync.Once
	all             chan struct{}
	count           int32
	requireShutdown bool
	t               *testing.T
}

func (l *gatedBurstLink) openGate() { l.gateOnce.Do(func() { close(l.gate) }) }
func (l *gatedBurstLink) Receive() (Frame, error) {
	if l.received.Load() == 1 {
		<-l.gate
	}
	f, err := l.memoryLink.Receive()
	if err == nil && l.received.Add(1) == l.count {
		close(l.all)
	}
	return f, err
}
func (l *gatedBurstLink) ShutdownReceive() error {
	l.openGate()
	return l.memoryLink.ShutdownReceive()
}
func (l *gatedBurstLink) Inject(f Frame) (int, error) {
	if l.requireShutdown {
		l.mu.Lock()
		stopped := len(l.calls) > 0 && l.calls[0] == "shutdown-receive"
		l.mu.Unlock()
		if !stopped {
			l.t.Error("restoration preceded receive shutdown")
		}
	}
	return l.memoryLink.Inject(f)
}

func TestBoundedBurstRetainsFIFOWhileFirstPacketIsPaced(t *testing.T) {
	for _, tc := range []struct {
		name           string
		count, backing int
	}{
		{"original-26-compact", 26, 100},
		{"previous-40-native-backing", 40, 65575},
	} {
		t.Run(tc.name, func(t *testing.T) { assertPacedBurst(t, tc.count, tc.backing, false) })
	}
}

func TestCancelledNativeBurstRestoresEveryOriginalAfterShutdown(t *testing.T) {
	assertPacedBurst(t, 40, 65575, true)
}

func assertPacedBurst(t *testing.T, count, backing int, cancelBeforeRelease bool) {
	t.Helper()
	start := time.Unix(0, 0)
	clock := &heldClock{now: start, entered: make(chan struct{}, count), release: make(chan struct{})}
	frames := make([]Frame, count)
	for i := range frames {
		frames[i] = testForwardFrame(100)
		storage := make([]byte, backing)
		copy(storage, frames[i].Packet)
		frames[i].Packet = storage[:100]
		frames[i].Packet[20] = byte(i)
		frames[i].Address.TimestampQPC = int64(i + 1)
	}
	link := &gatedBurstLink{memoryLink: memoryLink{frames: frames}, gate: make(chan struct{}), all: make(chan struct{}), count: int32(count), requireShutdown: cancelBeforeRelease, t: t}
	type outcome struct {
		r Receipt
		e error
	}
	done := make(chan outcome, 1)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		r, e := Run(ctx, Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: 10 * time.Second, DownlinkBytesPerSecond: 1000}, Limit, clock, func(Plan, Mode) (Link, error) { return link, nil })
		done <- outcome{r, e}
	}()
	select {
	case <-clock.entered:
	case result := <-done:
		t.Fatalf("stopped before pacing: %+v err=%v", result.r, result.e)
	case <-time.After(2 * time.Second):
		link.openGate()
		cancel()
		t.Fatal("first pacing wait not reached")
	}
	link.openGate()
	select {
	case <-link.all:
	case <-time.After(2 * time.Second):
		cancel()
		t.Fatal("bounded receiver stopped draining the offered burst")
	}
	if cancelBeforeRelease {
		cancel()
	} else {
		close(clock.release)
	}
	result := <-done
	if result.r.ReceivedPackets != uint64(count) || result.r.OwnedReceivedPackets != uint64(count) || result.r.ReceivedBytes != uint64(count*100) || result.r.UnconfirmedUserPackets != 0 || !result.r.DriverDrained || !result.r.OwnedHandleClosed || !result.r.WorkersJoined || result.r.PhoneReceivedBytes != nil || result.r.DriverDroppedPackets != nil {
		t.Fatalf("owned burst accounting incomplete: %+v err=%v", result.r, result.e)
	}
	if cancelBeforeRelease {
		if !errors.Is(result.e, context.Canceled) || result.r.State != "INVALID" || result.r.InjectedPackets != 0 || result.r.InjectedBytes != 0 || result.r.CleanupInjectedPackets != uint64(count) || result.r.CleanupInjectedBytes != uint64(count*100) || !clock.Now().Equal(start) {
			t.Fatalf("cancelled burst lost/restored early: %+v err=%v", result.r, result.e)
		}
	} else if result.e != nil || result.r.State != "INJECTED_NOT_CALIBRATED" || result.r.InjectedPackets != uint64(count) || result.r.InjectedBytes != uint64(count*100) || result.r.CleanupInjectedPackets != 0 || result.r.CleanupInjectedBytes != 0 {
		t.Fatalf("bounded burst was invalidated or not normally paced: %+v err=%v", result.r, result.e)
	}
	if len(link.injected) != count || (!cancelBeforeRelease && clock.Now().Sub(start) != time.Duration(count)*100*time.Millisecond) {
		t.Fatalf("pacing skipped: injected=%d elapsed=%s", len(link.injected), clock.Now().Sub(start))
	}
	for i, original := range frames {
		if !bytes.Equal(link.injected[i].Packet, original.Packet) || link.injected[i].Address != original.Address {
			t.Fatalf("packet/address/FIFO changed at %d", i)
		}
	}
}

func TestBackingBufferLimitIncludesHeldPacketAndRestoresOverflow(t *testing.T) {
	// 64 native-size allocations exceed 4MiB despite only 6400 IP bytes.
	// Counting len, or releasing at dequeue, would incorrectly accept this.
	const count = 64
	clock := &heldClock{now: time.Unix(0, 0), entered: make(chan struct{}, count), release: make(chan struct{})}
	frames := make([]Frame, count)
	for i := range frames {
		frames[i] = testForwardFrame(100)
		storage := make([]byte, 65575)
		copy(storage, frames[i].Packet)
		frames[i].Packet = storage[:100]
		frames[i].Packet[20] = byte(i)
		frames[i].Address.TimestampQPC = int64(i + 1)
	}
	link := &gatedBurstLink{memoryLink: memoryLink{frames: frames}, gate: make(chan struct{}), all: make(chan struct{}), count: count}
	type outcome struct {
		r Receipt
		e error
	}
	done := make(chan outcome, 1)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		r, e := Run(ctx, Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: 10 * time.Second, DownlinkBytesPerSecond: 1000}, Limit, clock, func(Plan, Mode) (Link, error) { return link, nil })
		done <- outcome{r, e}
	}()
	select {
	case <-clock.entered:
	case <-time.After(2 * time.Second):
		link.openGate()
		cancel()
		t.Fatal("first pacing wait not reached")
	}
	link.openGate()
	var result outcome
	select {
	case result = <-done:
	case <-time.After(2 * time.Second):
		cancel()
		close(clock.release)
		result = <-done
		t.Fatalf("buffer overflow did not stop promptly: %+v err=%v", result.r, result.e)
	}
	close(clock.release)
	if !errors.Is(result.e, ErrQueueOverflow) || result.r.StopReason != "USER_QUEUE_OVERFLOW" || result.r.State != "INVALID" || result.r.ReceivedPackets != count || result.r.InjectedPackets != 0 || result.r.CleanupInjectedPackets != count || result.r.CleanupInjectedBytes != count*100 || result.r.UnconfirmedUserPackets != 0 || !result.r.DriverDrained || !result.r.OwnedHandleClosed || !result.r.WorkersJoined {
		t.Fatalf("buffer limit bypassed or originals lost: %+v err=%v", result.r, result.e)
	}
	for i, original := range frames {
		if !bytes.Equal(link.injected[i].Packet, original.Packet) || link.injected[i].Address != original.Address {
			t.Fatalf("overflow cleanup changed FIFO/original at %d", i)
		}
	}
}

func TestInspectionAcknowledgesDiscardedOverflowCopyWithoutInjection(t *testing.T) {
	frame := testForwardFrame(20)
	storage := make([]byte, maxPendingBufferBytes+1)
	copy(storage, frame.Packet)
	frame.Packet = storage[:20]
	frame.Address.Word |= 0x10000
	link := &memoryLink{frames: []Frame{frame}}
	r, err := Run(context.Background(), Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000}, Inspect, &manualClock{now: time.Unix(0, 0)}, func(Plan, Mode) (Link, error) { return link, nil })
	if !errors.Is(err, ErrQueueOverflow) || r.State != "INVALID" || r.ReceivedPackets != 1 || r.OwnedReceivedPackets != 0 || r.UnconfirmedUserPackets != 0 || len(link.injected) != 0 || !r.DriverDrained || !r.OwnedHandleClosed || !r.WorkersJoined {
		t.Fatalf("overflow sniff copy injected, retained, or promoted: %+v err=%v", r, err)
	}
}

type refusedRestorationLink struct{ memoryLink }

func (l *refusedRestorationLink) Inject(Frame) (int, error) {
	return 0, errors.New("restoration refused")
}

func TestUnrestoredOverflowStopsAtAbandonWithoutReceivingAnotherFrame(t *testing.T) {
	frames := make([]Frame, maxPendingPackets+2)
	for i := range frames {
		frames[i] = testForwardFrame(100)
	}
	link := &refusedRestorationLink{memoryLink: memoryLink{frames: frames}}
	clock := &heldClock{now: time.Unix(0, 0), entered: make(chan struct{}, 1), release: make(chan struct{})}
	r, err := Run(context.Background(), Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: 10 * time.Second, DownlinkBytesPerSecond: 1000}, Limit, clock, func(Plan, Mode) (Link, error) { return link, nil })
	if !errors.Is(err, ErrQueueOverflow) || r.State != "INVALID" || r.ReceivedPackets != maxPendingPackets+1 || r.UnconfirmedUserPackets != maxPendingPackets+1 || r.DriverDrained || !r.WorkersJoined || !r.OwnedHandleClosed {
		t.Fatalf("pending overflow not bounded/abandoned or failure hidden: %+v err=%v", r, err)
	}
}
