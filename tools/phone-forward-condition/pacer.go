package condition

import (
	"context"
	"errors"
	"fmt"
	"sync/atomic"
	"time"
)

// Clock is the local scheduling boundary, never a phone/MP4 measurement clock.
type Clock interface {
	Now() time.Time
	WaitUntil(context.Context, time.Time) error
}

type Pacer struct {
	config  Config
	started time.Time
	last    time.Time
	clock   Clock
	stopped atomic.Bool
	busy    atomic.Bool
}
type Injection struct {
	Bytes            int
	ScheduledAfterNs int64
	InjectedAfterNs  int64
}

var ErrTTL = errors.New("condition TTL expired or packet cannot fit before expiry")
var ErrStopped = errors.New("condition is terminal and cannot resume")
var ErrConcurrent = errors.New("condition requires one injection owner; overlapping use terminated it")

func NewPacer(config Config, clock Clock) (*Pacer, error) {
	plan, err := NewPlan(config)
	if err != nil {
		return nil, err
	}
	start := clock.Now()
	return &Pacer{config: plan.Config, started: start, last: start, clock: clock}, nil
}

// Inject paces one unchanged IP packet for a single owner. Overlapping calls
// invalidate the condition rather than creating independent rate reservations.
// A successful callback only means
// injection into the host network stack, not receipt or goodput at the phone.
func (p *Pacer) Inject(ctx context.Context, packet []byte, send func([]byte) error) (result Injection, err error) {
	if !p.busy.CompareAndSwap(false, true) {
		p.stopped.Store(true)
		return Injection{}, ErrConcurrent
	}
	defer p.busy.Store(false)
	if p.stopped.Load() {
		return Injection{}, ErrStopped
	}
	defer func() {
		if err != nil {
			p.stopped.Store(true)
		}
	}()
	if len(packet) == 0 || len(packet) > 65575 {
		return Injection{}, fmt.Errorf("unsupported IP packet length: %d", len(packet))
	}
	if err := ctx.Err(); err != nil {
		return Injection{}, err
	}
	now := p.clock.Now()
	if now.Before(p.last) {
		return Injection{}, errors.New("local scheduling clock regressed")
	}
	delay := time.Duration((uint64(len(packet))*uint64(time.Second) + p.config.DownlinkBytesPerSecond - 1) / p.config.DownlinkBytesPerSecond)
	due := now.Add(delay)
	deadline := p.started.Add(p.config.TTL)
	if !due.Before(deadline) {
		return Injection{}, ErrTTL
	}
	if err := p.clock.WaitUntil(ctx, due); err != nil {
		return Injection{}, err
	}
	if err := ctx.Err(); err != nil {
		return Injection{}, err
	}
	actual := p.clock.Now()
	if p.stopped.Load() {
		return Injection{}, ErrStopped
	}
	if actual.Before(due) {
		return Injection{}, errors.New("local wait returned before scheduled injection")
	}
	if !actual.Before(deadline) {
		return Injection{}, ErrTTL
	}
	if err := send(packet); err != nil {
		return Injection{}, err
	}
	returned := p.clock.Now()
	result = Injection{Bytes: len(packet), ScheduledAfterNs: due.Sub(p.started).Nanoseconds(), InjectedAfterNs: returned.Sub(p.started).Nanoseconds()}
	if p.stopped.Load() {
		return result, ErrStopped
	}
	if returned.Before(actual) {
		return result, errors.New("local scheduling clock regressed during injection")
	}
	p.last = returned
	if !p.last.Before(deadline) {
		return result, ErrTTL
	}
	return result, nil
}
