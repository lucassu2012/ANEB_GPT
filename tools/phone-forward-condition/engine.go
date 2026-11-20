package condition

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sync"
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

type DirectionReceipt struct {
	ReceivedPackets        uint64
	ReceivedBytes          uint64
	OwnedReceivedPackets   uint64
	InjectedPackets        uint64
	InjectedBytes          uint64
	DroppedPackets         uint64
	DroppedBytes           uint64
	QueueDroppedPackets    uint64
	QueueDroppedBytes      uint64
	CleanupInjectedPackets uint64
	CleanupInjectedBytes   uint64
	UnconfirmedUserPackets uint64
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
	DroppedPackets         uint64
	DroppedBytes           uint64
	QueueDroppedPackets    uint64
	QueueDroppedBytes      uint64
	CleanupInjectedPackets uint64
	CleanupInjectedBytes   uint64
	UnconfirmedUserPackets uint64
	DriverDrained          bool
	OwnedHandleClosed      bool
	WorkersJoined          bool
	PhoneReceivedBytes     *uint64
	DriverDroppedPackets   *uint64
	Downlink               *DirectionReceipt `json:",omitempty"`
	Uplink                 *DirectionReceipt `json:",omitempty"`
	Failures               []string
}

type scopeMismatchError struct {
	err error
}

func (e scopeMismatchError) Error() string { return e.err.Error() }
func (e scopeMismatchError) Unwrap() error { return e.err }

const uplinkSeedDomain = uint64(0x55504c494e4b7634) // "UPLINKv4"

func splitMix64Next(z uint64) uint64 {
	z += 0x9e3779b97f4a7c15
	z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9
	z = (z ^ (z >> 27)) * 0x94d049bb133111eb
	return z ^ (z >> 31)
}

// shouldDrop applies a deterministic SplitMix64 hash over (seed, seq) against
// lossBps in [0, 10000] so packet-loss experiments are strictly reproducible.
func shouldDrop(seed, seq uint64, lossBps uint32) bool {
	if lossBps == 0 {
		return false
	}
	z := seed + seq*0x9e3779b97f4a7c15
	z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9
	z = (z ^ (z >> 27)) * 0x94d049bb133111eb
	z = z ^ (z >> 31)
	return (z % 10000) < uint64(lossBps)
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
	downPacer, err := NewDirectionalPacer(plan.Config.TTL, plan.Config.DownlinkBytesPerSecond, plan.Config.DownlinkDelay, clock)
	if err != nil {
		return receipt, err
	}
	var upPacer *Pacer
	if plan.Config.HasUplink() {
		upPacer, err = NewDirectionalPacer(plan.Config.TTL, plan.Config.UplinkBytesPerSecond, plan.Config.UplinkDelay, clock)
		if err != nil {
			return receipt, err
		}
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
		frame        Frame
		classErr     error
		receivedAt   time.Time
		reserved     bool
		overflowDone chan struct{}
	}
	type dirState struct {
		ch                     chan received
		pending                pendingBudget
		pacer                  *Pacer
		lossBps                uint32
		lossSeed               uint64
		held                   *received
		receivedPackets        atomic.Uint64
		receivedBytes          atomic.Uint64
		ownedReceivedPackets   atomic.Uint64
		queueDroppedPackets    atomic.Uint64
		queueDroppedBytes      atomic.Uint64
		injectedPackets        uint64
		injectedBytes          uint64
		droppedPackets         uint64
		droppedBytes           uint64
		cleanupInjectedPackets uint64
		cleanupInjectedBytes   uint64
	}
	newDirState := func(pacer *Pacer, lossBps uint32, lossSeed uint64) *dirState {
		return &dirState{
			ch:       make(chan received, maxPendingPackets),
			pending:  pendingBudget{limitPackets: maxPendingPackets, limitBytes: int(plan.Config.QueueLimitBytes)},
			pacer:    pacer,
			lossBps:  lossBps,
			lossSeed: lossSeed,
		}
	}
	settled := func(d *dirState, item received) {
		if item.reserved {
			d.pending.release(cap(item.frame.Packet))
		}
		if item.overflowDone != nil {
			close(item.overflowDone)
		}
	}
	down := newDirState(downPacer, plan.Config.DownlinkLossBasisPoints, plan.Config.ImpairmentSeed)
	var up *dirState
	if plan.Config.HasUplink() {
		upSeed := uint64(0)
		if plan.Config.UplinkLossBasisPoints > 0 {
			upSeed = splitMix64Next(plan.Config.UplinkImpairmentSeed ^ uplinkSeedDomain)
		}
		up = newDirState(upPacer, plan.Config.UplinkLossBasisPoints, upSeed)
	}

	done := make(chan struct{})
	abandon := make(chan struct{})
	var receivedCount, receivedBytes, ownedReceivedCount atomic.Uint64
	var queueDroppedPackets, queueDroppedBytes atomic.Uint64
	var driverDrained atomic.Bool
	var stopping atomic.Bool
	var activeCalls atomic.Int32
	var recvErrMu sync.Mutex
	var recvErr error
	var workerErrMu sync.Mutex
	var uncertainSendErr error
	var workerErr error
	recordUncertainSend := func(e error) {
		if e == nil {
			return
		}
		workerErrMu.Lock()
		if uncertainSendErr == nil {
			uncertainSendErr = e
		}
		workerErrMu.Unlock()
	}
	recordWorkerErr := func(e error) {
		if e == nil {
			return
		}
		workerErrMu.Lock()
		if workerErr == nil {
			workerErr = e
		}
		workerErrMu.Unlock()
	}
	var mismatchMu sync.Mutex
	var mismatchHeld *received
	setMismatchHeld := func(item *received) {
		mismatchMu.Lock()
		if mismatchHeld == nil {
			mismatchHeld = item
		}
		mismatchMu.Unlock()
	}
	var workersWg sync.WaitGroup

	go func() {
		defer close(done)
		defer func() {
			close(down.ch)
			if up != nil {
				close(up.ch)
			}
		}()
		for {
			frame, e := link.Receive()
			if e != nil {
				if errors.Is(e, io.EOF) {
					driverDrained.Store(true)
				} else {
					recvErrMu.Lock()
					if recvErr == nil {
						recvErr = e
					}
					recvErrMu.Unlock()
					cancel(e)
				}
				return
			}
			receivedCount.Add(1)
			receivedBytes.Add(uint64(len(frame.Packet)))
			if !frame.Address.IsSniffed() {
				ownedReceivedCount.Add(1)
			}
			dir, classErr := plan.ClassifyFrame(mode, frame)
			target := down
			if classErr == nil {
				if dir == DirectionUplink {
					target = up
				}
				target.receivedPackets.Add(1)
				target.receivedBytes.Add(uint64(len(frame.Packet)))
				if !frame.Address.IsSniffed() {
					target.ownedReceivedPackets.Add(1)
				}
			}
			item := received{
				frame:      frame,
				classErr:   classErr,
				receivedAt: clock.Now(),
				reserved:   target.pending.reserve(cap(frame.Packet)),
			}
			if !item.reserved {
				if classErr == nil && mode == Limit && plan.Config.QueueOverflowPolicy == QueueTailDrop && !frame.Address.IsSniffed() && !stopping.Load() {
					target.queueDroppedPackets.Add(1)
					target.queueDroppedBytes.Add(uint64(len(frame.Packet)))
					queueDroppedPackets.Add(1)
					queueDroppedBytes.Add(uint64(len(frame.Packet)))
					continue
				}
				if !stopping.Load() {
					cancel(ErrQueueOverflow)
				}
				// Receive already transferred this original. Keep exactly one
				// excess frame, and do not receive again before its disposal.
				item.overflowDone = make(chan struct{})
			}
			select {
			case target.ch <- item:
			case <-abandon:
				return
			}
			if classErr != nil && item.reserved && !stopping.Load() {
				cancel(scopeMismatchError{err: classErr})
			}
			if item.overflowDone != nil {
				select {
				case <-item.overflowDone:
				case <-abandon:
					return
				}
			}
		}
	}()

	defer func() {
		stopping.Store(true)
		cancel(nil)
		workersWg.Wait()
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cleanupCancel()
		_, shutdownErr, _ := boundedCall(cleanupCtx, &activeCalls, func() (int, error) { return 0, link.ShutdownReceive() })
		if e := shutdownErr; e != nil {
			err = errors.Join(err, e)
		}
		restore := func(d *dirState, item received, countOnDir bool) {
			frame := item.frame
			if mode != Limit || frame.Address.IsSniffed() {
				settled(d, item)
				return
			}
			n, e, _ := boundedCall(cleanupCtx, &activeCalls, func() (int, error) { return link.Inject(frame) })
			if n < 0 || n > len(frame.Packet) {
				n = 0
				e = errors.Join(e, errors.New("invalid cleanup injection byte count"))
			}
			if n > 0 {
				receipt.CleanupInjectedBytes += uint64(n)
				if countOnDir {
					d.cleanupInjectedBytes += uint64(n)
				}
			}
			if e == nil && n == len(frame.Packet) {
				receipt.CleanupInjectedPackets++
				if countOnDir {
					d.cleanupInjectedPackets++
				}
				settled(d, item)
			} else {
				if e == nil {
					e = io.ErrShortWrite
				}
				err = errors.Join(err, e)
			}
		}
		if shutdownErr == nil {
			if mismatchHeld != nil {
				restore(down, *mismatchHeld, false)
			}
			if down.held != nil {
				restore(down, *down.held, true)
			}
			if up != nil && up.held != nil {
				restore(up, *up.held, true)
			}
			downOpen := true
			upOpen := up != nil
			for downOpen || upOpen {
				var dCh, uCh <-chan received
				if downOpen {
					dCh = down.ch
				}
				if upOpen {
					uCh = up.ch
				}
				select {
				case <-cleanupCtx.Done():
					err = errors.Join(err, errors.New("owned queue drain exceeded cleanup budget"))
					downOpen = false
					upOpen = false
				case item, ok := <-dCh:
					if !ok {
						downOpen = false
						continue
					}
					restore(down, item, item.classErr == nil)
				case item, ok := <-uCh:
					if !ok {
						upOpen = false
						continue
					}
					restore(up, item, item.classErr == nil)
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

		finalizeDir := func(d *dirState) *DirectionReceipt {
			dr := &DirectionReceipt{
				ReceivedPackets:        d.receivedPackets.Load(),
				ReceivedBytes:          d.receivedBytes.Load(),
				OwnedReceivedPackets:   d.ownedReceivedPackets.Load(),
				InjectedPackets:        d.injectedPackets,
				InjectedBytes:          d.injectedBytes,
				DroppedPackets:         d.droppedPackets,
				DroppedBytes:           d.droppedBytes,
				QueueDroppedPackets:    d.queueDroppedPackets.Load(),
				QueueDroppedBytes:      d.queueDroppedBytes.Load(),
				CleanupInjectedPackets: d.cleanupInjectedPackets,
				CleanupInjectedBytes:   d.cleanupInjectedBytes,
			}
			dr.UnconfirmedUserPackets = dr.OwnedReceivedPackets - dr.InjectedPackets - dr.DroppedPackets - dr.QueueDroppedPackets - dr.CleanupInjectedPackets
			return dr
		}

		downSummary := finalizeDir(down)
		receipt.InjectedPackets = downSummary.InjectedPackets
		receipt.InjectedBytes = downSummary.InjectedBytes
		receipt.DroppedPackets = downSummary.DroppedPackets
		receipt.DroppedBytes = downSummary.DroppedBytes
		if up != nil {
			upSummary := finalizeDir(up)
			receipt.InjectedPackets += upSummary.InjectedPackets
			receipt.InjectedBytes += upSummary.InjectedBytes
			receipt.DroppedPackets += upSummary.DroppedPackets
			receipt.DroppedBytes += upSummary.DroppedBytes
			receipt.Downlink = downSummary
			receipt.Uplink = upSummary
		}

		receipt.ReceivedPackets = receivedCount.Load()
		receipt.ReceivedBytes = receivedBytes.Load()
		receipt.OwnedReceivedPackets = ownedReceivedCount.Load()
		receipt.QueueDroppedPackets = queueDroppedPackets.Load()
		receipt.QueueDroppedBytes = queueDroppedBytes.Load()
		receipt.DriverDrained = driverDrained.Load()
		receipt.UnconfirmedUserPackets = receipt.OwnedReceivedPackets - receipt.InjectedPackets - receipt.DroppedPackets - receipt.QueueDroppedPackets - receipt.CleanupInjectedPackets
		workerErrMu.Lock()
		uErr := uncertainSendErr
		wErr := workerErr
		workerErrMu.Unlock()
		if uErr != nil {
			if err == nil {
				err = uErr
			} else if !errors.Is(err, uErr) {
				err = errors.Join(err, uErr)
			}
			if receipt.StopReason == "" || receipt.StopReason == "TTL_EXPIRED" || receipt.StopReason == "LINK_EOF" {
				receipt.StopReason = uncertainSendStopReason(uErr)
			}
		} else if wErr != nil {
			if err == nil {
				err = wErr
			} else if !errors.Is(err, wErr) {
				err = errors.Join(err, wErr)
			}
			if receipt.StopReason == "" || receipt.StopReason == "TTL_EXPIRED" || receipt.StopReason == "LINK_EOF" {
				receipt.StopReason = wErr.Error()
			}
		}
		if receipt.UnconfirmedUserPackets != 0 ||
			(receipt.Downlink != nil && receipt.Downlink.UnconfirmedUserPackets != 0) ||
			(receipt.Uplink != nil && receipt.Uplink.UnconfirmedUserPackets != 0) {
			if err == nil {
				err = fmt.Errorf("packet conservation failed: %d unconfirmed user packets", receipt.UnconfirmedUserPackets)
			}
			if receipt.StopReason == "" || receipt.StopReason == "TTL_EXPIRED" || receipt.StopReason == "LINK_EOF" {
				receipt.StopReason = "UNCONFIRMED_USER_PACKETS"
			}
		}
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

	runDirectionWorker := func(d *dirState) {
		defer workersWg.Done()
		var limitSeq uint64
		for {
			if ctx.Err() != nil {
				return
			}
			var current received
			var ok bool
			select {
			case <-ctx.Done():
				return
			case current, ok = <-d.ch:
				if !ok {
					return
				}
			}
			if current.classErr != nil {
				if mode == Limit && !current.frame.Address.IsSniffed() {
					setMismatchHeld(&current)
				} else {
					settled(d, current)
				}
				cancel(scopeMismatchError{err: current.classErr})
				return
			}
			if ctx.Err() != nil {
				if mode == Limit && !current.frame.Address.IsSniffed() {
					d.held = &current
				} else {
					settled(d, current)
				}
				return
			}
			if mode == Inspect {
				settled(d, current)
				continue
			}
			limitSeq++
			if d.lossBps > 0 && shouldDrop(d.lossSeed, limitSeq, d.lossBps) {
				d.droppedPackets++
				d.droppedBytes += uint64(len(current.frame.Packet))
				settled(d, current)
				continue
			}
			frame := current.frame
			attempted := false
			var sendFailed error
			_, injectErr := d.pacer.InjectAt(ctx, current.receivedAt, frame.Packet, func(packet []byte) error {
				frame.Packet = packet
				n, sendErr, admitted := boundedCall(ctx, &activeCalls, func() (int, error) { return link.Inject(frame) })
				attempted = admitted
				if n < 0 || n > len(packet) {
					n = 0
					sendErr = errors.Join(sendErr, errors.New("invalid injection byte count"))
				}
				if n > 0 {
					d.injectedBytes += uint64(n)
				}
				if sendErr == nil && n != len(packet) {
					sendErr = io.ErrShortWrite
				}
				if sendErr == nil {
					d.injectedPackets++
					// Confirmed send is the release boundary, not dequeue or the
					// later Pacer TTL/clock verdict. An uncertain send keeps its lease.
					settled(d, current)
				} else if admitted {
					sendFailed = sendErr
				}
				return sendErr
			})
			if injectErr != nil {
				if !attempted {
					d.held = &current
					// A not-yet-dispatched packet that cannot fit is retained until
					// the original cutoff, then restored in cleanup, not paced early.
					// Never normalize an admitted/uncertain native send or parent stop.
					if errors.Is(injectErr, ErrTTL) || (errors.Is(injectErr, context.DeadlineExceeded) && errors.Is(context.Cause(ctx), errPlannedTTL)) {
						<-ctx.Done()
						if errors.Is(context.Cause(ctx), errPlannedTTL) {
							return
						}
					}
					if !errors.Is(injectErr, ErrTTL) && !errors.Is(injectErr, context.DeadlineExceeded) && !errors.Is(injectErr, context.Canceled) {
						recordWorkerErr(injectErr)
					}
				} else if sendFailed != nil {
					recordUncertainSend(sendFailed)
				} else if errors.Is(injectErr, ErrTTL) {
					// link.Inject confirmed delivery (settled already ran), and pacer
					// observed clock >= deadline afterward. Wait for the planned cutoff
					// without cancelling concurrent directions or masking real causes.
					<-ctx.Done()
					if errors.Is(context.Cause(ctx), errPlannedTTL) {
						return
					}
				} else {
					recordWorkerErr(injectErr)
				}
				cancel(injectErr)
				return
			}
		}
	}

	workersWg.Add(1)
	go runDirectionWorker(down)
	if up != nil {
		workersWg.Add(1)
		go runDirectionWorker(up)
	}
	workersDone := make(chan struct{})
	go func() {
		workersWg.Wait()
		close(workersDone)
	}()

	select {
	case <-workersDone:
	case <-ctx.Done():
	}

	recvErrMu.Lock()
	rErr := recvErr
	recvErrMu.Unlock()
	if rErr != nil {
		receipt.StopReason = rErr.Error()
		return receipt, rErr
	}
	workerErrMu.Lock()
	uErr := uncertainSendErr
	wErr := workerErr
	workerErrMu.Unlock()
	if cause := context.Cause(ctx); cause != nil {
		if errors.Is(cause, errPlannedTTL) {
			if uErr != nil {
				receipt.StopReason = uncertainSendStopReason(uErr)
				return receipt, uErr
			}
			if wErr != nil {
				receipt.StopReason = wErr.Error()
				return receipt, wErr
			}
			receipt.StopReason = "TTL_EXPIRED"
			return receipt, nil
		}
		var sm scopeMismatchError
		if errors.As(cause, &sm) {
			receipt.StopReason = "PACKET_SCOPE_MISMATCH"
			return receipt, sm.err
		}
		if uErr != nil {
			receipt.StopReason = uncertainSendStopReason(uErr)
			return receipt, cause
		}
		receipt.StopReason = cause.Error()
		return receipt, cause
	}
	if uErr != nil {
		receipt.StopReason = uncertainSendStopReason(uErr)
		return receipt, uErr
	}
	if wErr != nil {
		receipt.StopReason = wErr.Error()
		return receipt, wErr
	}
	receipt.StopReason = "LINK_EOF"
	return receipt, nil
}

func uncertainSendStopReason(err error) string {
	if errors.Is(err, context.DeadlineExceeded) {
		return "INJECT_TIMEOUT"
	}
	return "INJECT_ERROR"
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
