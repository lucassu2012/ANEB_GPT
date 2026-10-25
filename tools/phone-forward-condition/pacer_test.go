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

type manualClock struct {
	now   time.Time
	waits []time.Time
}

func (c *manualClock) Now() time.Time { return c.now }
func (c *manualClock) WaitUntil(ctx context.Context, due time.Time) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	c.waits = append(c.waits, due)
	c.now = due
	return nil
}

func TestPacerSerializesIPBytesWithoutInitialBurstOrPayloadRewrite(t *testing.T) {
	clock := &manualClock{now: time.Unix(0, 0)}
	p, err := NewPacer(Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000}, clock)
	if err != nil {
		t.Fatal(err)
	}
	packet := bytes.Repeat([]byte{0x5a}, 100)
	var actual []time.Duration
	for range 2 {
		injection, err := p.Inject(context.Background(), packet, func(got []byte) error {
			if !bytes.Equal(got, packet) {
				t.Fatal("packet rewritten")
			}
			actual = append(actual, clock.Now().Sub(time.Unix(0, 0)))
			return nil
		})
		if err != nil {
			t.Fatal(err)
		}
		if injection.Bytes != 100 {
			t.Fatal(injection)
		}
	}
	if len(actual) != 2 || actual[0] != 100*time.Millisecond || actual[1] != 200*time.Millisecond {
		t.Fatalf("actual injection times %v, want100ms,200ms", actual)
	}
}

func TestPacerDoesNotInjectAfterExpiryCancellationOrClockRegression(t *testing.T) {
	for _, test := range []struct {
		name    string
		advance time.Duration
		cancel  bool
		size    int
	}{
		{"expired", time.Second, false, 100},
		{"does_not_fit", 950 * time.Millisecond, false, 100},
		{"cancelled", 0, true, 100},
		{"clock_regressed", -time.Nanosecond, false, 100},
		{"zero_packet", 0, false, 0},
		{"truncated_or_oversized", 0, false, 65576},
	} {
		t.Run(test.name, func(t *testing.T) {
			clock := &manualClock{now: time.Unix(0, 0)}
			p, err := NewPacer(Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000}, clock)
			if err != nil {
				t.Fatal(err)
			}
			clock.now = clock.now.Add(test.advance)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if test.cancel {
				cancel()
			}
			called := false
			_, err = p.Inject(ctx, make([]byte, test.size), func([]byte) error { called = true; return nil })
			if err == nil || called {
				t.Fatalf("err=%v, injected=%t", err, called)
			}
		})
	}
}

func TestPacerRetainsInjectionFailureInsteadOfClaimingDelivery(t *testing.T) {
	clock := &manualClock{now: time.Unix(0, 0)}
	p, _ := NewPacer(Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000}, clock)
	want := errors.New("host injection failed")
	got, err := p.Inject(context.Background(), []byte{1}, func([]byte) error { return want })
	if !errors.Is(err, want) || got.Bytes != 0 {
		t.Fatalf("got%+v err%v", got, err)
	}
}

func TestPacerRejectsClockRegressionDuringInjection(t *testing.T) {
	clock := &manualClock{now: time.Unix(0, 0)}
	p, _ := NewPacer(Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000}, clock)
	result, err := p.Inject(context.Background(), []byte{1}, func([]byte) error { clock.now = time.Unix(0, 0).Add(-time.Second); return nil })
	if err == nil || result.Bytes != 1 {
		t.Fatalf("clock fault hidden or actual host injection forgotten: %+v, %v", result, err)
	}
}

func TestPacerCannotResumeAfterAnInjectionFailure(t *testing.T) {
	clock := &manualClock{now: time.Unix(0, 0)}
	p, _ := NewPacer(Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: time.Second, DownlinkBytesPerSecond: 1000}, clock)
	_, err := p.Inject(context.Background(), []byte{1}, func([]byte) error { return errors.New("injection failure") })
	if err == nil {
		t.Fatal("initial error hidden")
	}
	called := false
	_, err = p.Inject(context.Background(), []byte{1}, func([]byte) error { called = true; return nil })
	if err == nil || called {
		t.Fatalf("failed condition resumed: err=%v injected=%t", err, called)
	}
}

type heldClock struct {
	mu      sync.Mutex
	now     time.Time
	entered chan struct{}
	release chan struct{}
}

func (c *heldClock) Now() time.Time { c.mu.Lock(); defer c.mu.Unlock(); return c.now }
func (c *heldClock) WaitUntil(ctx context.Context, due time.Time) error {
	c.entered <- struct{}{}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-c.release:
	}
	c.mu.Lock()
	c.now = due
	c.mu.Unlock()
	return nil
}

func TestConcurrentInjectionCannotExceedOneConditionRate(t *testing.T) {
	clock := &heldClock{now: time.Unix(0, 0), entered: make(chan struct{}, 2), release: make(chan struct{})}
	p, _ := NewPacer(Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: 3 * time.Second, DownlinkBytesPerSecond: 100}, clock)
	var sends atomic.Int32
	first := make(chan error, 1)
	second := make(chan error, 1)
	call := func(ch chan error) {
		_, err := p.Inject(context.Background(), make([]byte, 100), func([]byte) error { sends.Add(1); return nil })
		ch <- err
	}
	go call(first)
	<-clock.entered
	go call(second)
	var secondErr error
	returned := false
	select {
	case secondErr = <-second:
		returned = true
		if secondErr == nil {
			t.Error("concurrent call accepted")
		}
	case <-time.After(200 * time.Millisecond):
		t.Error("concurrent call entered pacing instead of being rejected")
	}
	close(clock.release)
	firstErr := <-first
	if !returned {
		secondErr = <-second
	}
	if firstErr == nil || secondErr == nil || sends.Load() != 0 {
		t.Fatalf("overlapping condition did not terminate: first=%v second=%v sends=%d", firstErr, secondErr, sends.Load())
	}
}
