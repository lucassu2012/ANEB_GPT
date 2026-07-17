package gateway

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
)

type recordedCommand struct {
	name string
	args []string
}

type fakeExecutor struct {
	mu       sync.Mutex
	commands []recordedCommand
	stale    bool
}

func (f *fakeExecutor) Run(_ context.Context, name string, args ...string) ([]byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.commands = append(f.commands, recordedCommand{name, append([]string(nil), args...)})
	joined := name + " " + strings.Join(args, " ")
	if strings.HasPrefix(joined, "tc qdisc show") {
		if f.stale {
			return []byte("qdisc netem 1: root"), nil
		}
		return []byte("qdisc noqueue 0: root"), nil
	}
	if strings.HasPrefix(joined, "ip link show") {
		return []byte("Device not found"), errors.New("exit status 1")
	}
	return nil, nil
}

func (f *fakeExecutor) contains(fragment string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, command := range f.commands {
		if strings.Contains(command.name+" "+strings.Join(command.args, " "), fragment) {
			return true
		}
	}
	return false
}

func TestTCControllerAppliesWANEgressAndIFBDownlink(t *testing.T) {
	executor := &fakeExecutor{}
	controller := TCController{WAN: "eth0", IFB: "ifb-aneb0", Executor: executor}
	profile := validProfile()
	profile.Uplink = DirectionPolicy{RateMbps: 2, DelayMs: 50, JitterMs: 10, LossPct: 1}
	profile.Downlink = DirectionPolicy{RateMbps: 5, DelayMs: 60, JitterMs: 20, LossPct: 2}
	if err := controller.Apply(context.Background(), profile); err != nil {
		t.Fatal(err)
	}
	for _, expected := range []string{
		"tc filter replace dev eth0 parent ffff:",
		"redirect dev ifb-aneb0",
		"dev eth0 root handle 1: netem limit 100000 delay 50ms 10ms loss 1% rate 2mbit",
		"dev ifb-aneb0 root handle 1: netem limit 100000 delay 60ms 20ms loss 2% rate 5mbit",
	} {
		if !executor.contains(expected) {
			t.Fatalf("missing command fragment %q", expected)
		}
	}
}

func TestTCControllerFailsClosedWhenCleanupStillHasNetem(t *testing.T) {
	executor := &fakeExecutor{stale: true}
	controller := TCController{WAN: "eth0", IFB: "ifb-aneb0", Executor: executor}
	if err := controller.Clear(context.Background()); err == nil || !strings.Contains(err.Error(), "still present") {
		t.Fatalf("cleanup error=%v", err)
	}
	if err := controller.Apply(context.Background(), validProfile()); err == nil || !strings.Contains(err.Error(), "pre-apply cleanup") {
		t.Fatalf("apply error=%v", err)
	}
}
