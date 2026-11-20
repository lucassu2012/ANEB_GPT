package condition

import "sync"

// Fixed engineering resource limits, not calibrated network quality. The
// budget includes queued, held and in-flight frames until a send is confirmed.
// cap, not len, charges actual allocated backing capacity; native Receive
// right-sizes each frame so 4 MiB covers realistic multi-flow video bursts
// up to maxPendingPackets without exceeding the fixed 4 MiB memory bound.
const maxPendingPackets = 2048
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
