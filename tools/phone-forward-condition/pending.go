package condition

import "sync"

// Fixed engineering resource limits, not calibrated network quality. The
// budget includes queued, held and in-flight frames until a send is confirmed.
// cap, not len, charges the native receiver's full backing buffer (65575 bytes
// even for a short IP packet). One just-received overflow frame is handled by
// an acknowledged producer slot; it cannot turn into an unbounded extra queue.
const maxPendingPackets = 256
const maxPendingBufferBytes = 4 << 20

type pendingBudget struct {
	mu      sync.Mutex
	packets int
	bytes   int
}

func (b *pendingBudget) reserve(memory int) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.packets >= maxPendingPackets || memory > maxPendingBufferBytes-b.bytes {
		return false
	}
	b.packets++
	b.bytes += memory
	return true
}

func (b *pendingBudget) release(memory int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.packets--
	b.bytes -= memory
}
