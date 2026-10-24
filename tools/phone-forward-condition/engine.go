package condition

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sync/atomic"
	"time"
)

type Mode string

const (
	Inspect Mode = "inspect"
	Limit   Mode = "limit"
)

var ErrQueueOverflow = errors.New("owned packet queue overflowed; condition invalid")
var errPlannedTTL = errors.New("planned condition lifetime ended")

type Frame struct {
	Packet  []byte
	Address Address
}

// Link is the real forwarding system boundary. Receive transfers an owned copy;
// Inject reports host-stack bytes only. ShutdownReceive must keep Inject open.
type Link interface {
	Receive() (Frame, error)
	Inject(Frame) (int, error)
	ShutdownReceive() error
	Close() error
}

type Receipt struct {
	Mode                   Mode
	State                  string
	StopReason             string
	ReceivedPackets        uint64
	ReceivedBytes          uint64
	OwnedReceivedPackets   uint64
	InjectedPackets        uint64
	InjectedBytes          uint64
	CleanupInjectedPackets uint64
	CleanupInjectedBytes   uint64
	UnconfirmedUserPackets uint64
	DriverDrained          bool
	OwnedHandleClosed      bool
	WorkersJoined          bool
	PhoneReceivedBytes     *uint64
	DriverDroppedPackets   *uint64
	Failures               []string
}

func Run(ctx context.Context, config Config, mode Mode, clock Clock, open func(Plan, Mode) (Link, error)) (receipt Receipt, err error) {
	receipt = Receipt{Mode: mode, State: "NOT_STARTED"}
	plan, err := NewPlan(config)
	if err != nil {
		return receipt, err
	}
	if mode != Inspect && mode != Limit {
		return receipt, fmt.Errorf("unsupported mode: %q", mode)
	}
	pacer, err := NewPacer(plan.Config, clock)
	if err != nil {
		return receipt, err
	}
	lifetimeCtx, lifetimeCancel := context.WithTimeoutCause(ctx, plan.Config.TTL, errPlannedTTL)
	defer lifetimeCancel()
	ctx, cancel := context.WithCancelCause(lifetimeCtx)
	defer cancel(nil)
	if err = ctx.Err(); err != nil {
		return receipt, err
	}
	link, err := open(plan, mode)
	if link == nil {
		if err == nil {
			err = errors.New("opener returned no owned link")
		}
		return receipt, err
	}
	openErr := err
	type received struct {
		frame Frame
		err   error
	}
	packets := make(chan received, 16)
	done := make(chan struct{})
	abandon := make(chan struct{})
	var receivedCount, receivedBytes, ownedReceivedCount atomic.Uint64
	var driverDrained atomic.Bool
	var stopping atomic.Bool
	var activeCalls atomic.Int32
	var held *Frame
	go func() {
		defer close(done)
		defer close(packets)
		for {
			frame, e := link.Receive()
			if e == nil {
				receivedCount.Add(1)
				receivedBytes.Add(uint64(len(frame.Packet)))
				if !frame.Address.IsSniffed() {
					ownedReceivedCount.Add(1)
				}
			}
			if errors.Is(e, io.EOF) {
				driverDrained.Store(true)
			}
			item := received{frame, e}
			if e == nil && !stopping.Load() {
				select {
				case packets <- item:
					continue
				default:
					cancel(ErrQueueOverflow)
				}
			}
			select {
			case packets <- item:
			case <-abandon:
				return
			}
			if e != nil {
				return
			}
		}
	}()
	defer func() {
		stopping.Store(true)
		cancel(nil)
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cleanupCancel()
		_, shutdownErr, _ := boundedCall(cleanupCtx, &activeCalls, func() (int, error) { return 0, link.ShutdownReceive() })
		if e := shutdownErr; e != nil {
			err = errors.Join(err, e)
		}
		restore := func(frame Frame) {
			if mode != Limit || frame.Address.IsSniffed() {
				return
			}
			n, e, _ := boundedCall(cleanupCtx, &activeCalls, func() (int, error) { return link.Inject(frame) })
			if n < 0 || n > len(frame.Packet) {
				n = 0
				e = errors.Join(e, errors.New("invalid cleanup injection byte count"))
			}
			if n > 0 {
				receipt.CleanupInjectedBytes += uint64(n)
			}
			if e == nil && n == len(frame.Packet) {
				receipt.CleanupInjectedPackets++
			} else {
				if e == nil {
					e = io.ErrShortWrite
				}
				err = errors.Join(err, e)
			}
		}
		if shutdownErr == nil {
			if held != nil {
				restore(*held)
			}
		drain:
			for {
				select {
				case <-cleanupCtx.Done():
					err = errors.Join(err, errors.New("owned queue drain exceeded cleanup budget"))
					break drain
				case item, ok := <-packets:
					if !ok {
						break drain
					}
					if item.err != nil {
						if !errors.Is(item.err, io.EOF) {
							err = errors.Join(err, item.err)
						}
						continue
					}
					restore(item.frame)
				}
			}
		}
		close(abandon)
		closeCtx, closeCancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
		defer closeCancel()
		_, closeErr, _ := boundedCall(closeCtx, &activeCalls, func() (int, error) { return 0, link.Close() })
		if e := closeErr; e != nil {
			err = errors.Join(err, e)
		} else {
			receipt.OwnedHandleClosed = true
		}
		select {
		case <-done:
			receipt.WorkersJoined = activeCalls.Load() == 0
		case <-closeCtx.Done():
		}
		if !receipt.WorkersJoined {
			err = errors.Join(err, errors.New("owned calls or receiver not joined"))
		}
		receipt.ReceivedPackets = receivedCount.Load()
		receipt.ReceivedBytes = receivedBytes.Load()
		receipt.OwnedReceivedPackets = ownedReceivedCount.Load()
		receipt.DriverDrained = driverDrained.Load()
		receipt.UnconfirmedUserPackets = receipt.OwnedReceivedPackets - receipt.InjectedPackets - receipt.CleanupInjectedPackets
		if err != nil {
			if errors.Is(err, ErrQueueOverflow) {
				receipt.StopReason = "USER_QUEUE_OVERFLOW"
			}
			receipt.State = "INVALID"
			receipt.Failures = append(receipt.Failures, err.Error())
		} else if receipt.ReceivedPackets == 0 {
			receipt.State = "NO_MATCHING_PACKETS"
		} else if mode == Inspect {
			receipt.State = "OBSERVED_NOT_QUALIFIED"
		} else if receipt.InjectedPackets == 0 {
			receipt.State = "NO_PACED_PACKETS"
		} else {
			receipt.State = "INJECTED_NOT_CALIBRATED"
		}
	}()
	if openErr != nil {
		receipt.StopReason = "OPEN_SETUP_FAILED"
		return receipt, openErr
	}
	for {
		var current received
		select {
		case <-ctx.Done():
			if errors.Is(context.Cause(ctx), errPlannedTTL) {
				receipt.StopReason = "TTL_EXPIRED"
				goto complete
			}
			receipt.StopReason = context.Cause(ctx).Error()
			return receipt, context.Cause(ctx)
		case item, ok := <-packets:
			if !ok {
				receipt.StopReason = "LINK_EOF"
				goto complete
			}
			current = item
		}
		frame, e := current.frame, current.err
		if errors.Is(e, io.EOF) {
			receipt.DriverDrained = true
			receipt.StopReason = "LINK_EOF"
			break
		}
		if e != nil {
			return receipt, e
		}
		if e := plan.CheckFrame(mode, frame); e != nil {
			if mode == Limit && !frame.Address.IsSniffed() {
				held = &frame
			}
			receipt.StopReason = "PACKET_SCOPE_MISMATCH"
			return receipt, e
		}
		if mode == Inspect {
			continue
		}
		attempted := false
		_, e = pacer.Inject(ctx, frame.Packet, func(packet []byte) error {
			frame.Packet = packet
			n, sendErr, admitted := boundedCall(ctx, &activeCalls, func() (int, error) { return link.Inject(frame) })
			attempted = admitted
			if n < 0 || n > len(packet) {
				n = 0
				sendErr = errors.Join(sendErr, errors.New("invalid injection byte count"))
			}
			if n > 0 {
				receipt.InjectedBytes += uint64(n)
			}
			if sendErr == nil && n != len(packet) {
				sendErr = io.ErrShortWrite
			}
			if sendErr == nil {
				receipt.InjectedPackets++
			}
			return sendErr
		})
		if e != nil {
			if !attempted {
				held = &frame
				// A not-yet-dispatched packet that cannot fit is retained until
				// the original cutoff, then restored in cleanup, not paced early.
				// Never normalize an admitted/uncertain native send or parent stop.
				if errors.Is(e, ErrTTL) || (errors.Is(e, context.DeadlineExceeded) && errors.Is(context.Cause(ctx), errPlannedTTL)) {
					<-ctx.Done()
					if errors.Is(context.Cause(ctx), errPlannedTTL) {
						receipt.StopReason = "TTL_EXPIRED"
						goto complete
					}
				}
			}
			e = errors.Join(e, context.Cause(ctx))
			receipt.StopReason = e.Error()
			return receipt, e
		}
	}
complete:
	if e := context.Cause(ctx); e != nil && !errors.Is(e, errPlannedTTL) {
		return receipt, e
	}
	return receipt, nil
}

// A timed-out system call is unconfirmed, never retried. The independent worker
// supervisor must terminate the owned process if native calls fail to join.
func boundedCall(ctx context.Context, active *atomic.Int32, call func() (int, error)) (int, error, bool) {
	if err := ctx.Err(); err != nil {
		return 0, err, false
	}
	type result struct {
		n   int
		err error
	}
	returned := make(chan result, 1)
	active.Add(1)
	go func() { n, err := call(); active.Add(-1); returned <- result{n, err} }()
	select {
	case r := <-returned:
		return r.n, r.err, true
	case <-ctx.Done():
		return 0, ctx.Err(), true
	}
}
