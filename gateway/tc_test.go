package gateway

import (
	"context"
	"errors"
	"os"
	"strings"
	"sync"
	"testing"
)

type recordedCommand struct {
	name string
	args []string
}

type ownershipExecutor struct {
	mu            sync.Mutex
	commands      []recordedCommand
	rootOwned     bool
	ingressOwned  bool
	clsact        bool
	filterDeleted bool
	ifbExists     bool
	ifbAlias      string
	ifbKind       string
	foreignFilter bool
	baselineQdisc string
	failures      map[string]int
}

func (f *ownershipExecutor) Run(_ context.Context, name string, args ...string) ([]byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.commands = append(f.commands, recordedCommand{name, append([]string(nil), args...)})
	joined := name + " " + strings.Join(args, " ")
	for fragment, remaining := range f.failures {
		if remaining > 0 && strings.Contains(joined, fragment) {
			f.failures[fragment] = remaining - 1
			return []byte("injected failure"), errors.New("injected failure")
		}
	}
	switch {
	case strings.Contains(joined, "qdisc show dev eth0"):
		lines := []string{}
		if f.rootOwned {
			lines = append(lines, "qdisc netem 1a1e: root limit 100000")
		} else {
			baseline := f.baselineQdisc
			if baseline == "" {
				baseline = "qdisc noqueue 0: root refcnt 2"
			}
			lines = append(lines, baseline)
		}
		if f.ingressOwned {
			lines = append(lines, "qdisc ingress ffff: parent ffff:fff1")
		}
		if f.clsact {
			lines = append(lines, "qdisc clsact ffff: parent ffff:fff1")
		}
		return []byte(strings.Join(lines, "\n")), nil
	case strings.Contains(joined, "filter show dev eth0 parent ffff:"):
		if f.foreignFilter {
			return []byte("filter protocol all pref 49152 u32 chain 0\nfilter protocol all pref 49152 u32 chain 0 fh 800::800 order 2048 key ht 800 bkt 0 terminal\n  match 00000000/00000000 at 0\n\taction order 1: gact action drop"), nil
		}
		if f.ingressOwned && !f.filterDeleted {
			return []byte("filter protocol all pref 49152 u32 chain 0\nfilter protocol all pref 49152 u32 chain 0 fh 800: ht divisor 1\nfilter protocol all pref 49152 u32 chain 0 fh 800::800 order 2048 key ht 800 bkt 0 terminal flowid ??? not_in_hw\n  match 00000000/00000000 at 0\n\taction order 1: mirred (Egress Redirect to device ifb-aneb0) stolen\n\tindex 1 ref 1 bind 1"), nil
		}
		return nil, nil
	case strings.Contains(joined, " link show dev ifb-aneb0"):
		if !f.ifbExists {
			return []byte("Device does not exist"), errors.New("exit status 1")
		}
		kind := f.ifbKind
		if kind == "" {
			kind = "ifb"
		}
		return []byte(`[{"ifname":"ifb-aneb0","ifalias":"` + f.ifbAlias + `","linkinfo":{"info_kind":"` + kind + `"}}]`), nil
	case strings.Contains(joined, "link add ifb-aneb0 type ifb"):
		f.ifbExists = true
		f.ifbKind = "ifb"
	case strings.Contains(joined, "link set dev ifb-aneb0 alias"):
		f.ifbAlias = anebIFBAlias
	case strings.Contains(joined, "qdisc replace dev eth0 handle ffff: ingress"):
		f.ingressOwned = true
		f.filterDeleted = true
	case strings.Contains(joined, "filter replace dev eth0 parent ffff:"):
		f.filterDeleted = false
	case strings.Contains(joined, "qdisc replace dev eth0 root handle 1a1e: netem"):
		f.rootOwned = true
	case strings.Contains(joined, "qdisc del dev eth0 root"):
		f.rootOwned = false
	case strings.Contains(joined, "qdisc replace dev eth0 root fq"):
		f.rootOwned = false
	case strings.Contains(joined, "filter del dev eth0 parent ffff:"):
		f.filterDeleted = true
	case strings.Contains(joined, "qdisc del dev eth0 ingress"):
		f.ingressOwned = false
	case strings.Contains(joined, "link del ifb-aneb0"):
		f.ifbExists = false
	}
	return nil, nil
}

func (f *ownershipExecutor) contains(fragment string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, command := range f.commands {
		if strings.Contains(command.name+" "+strings.Join(command.args, " "), fragment) {
			return true
		}
	}
	return false
}

func (f *ownershipExecutor) commandIndex(fragment string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	for index, command := range f.commands {
		if strings.Contains(command.name+" "+strings.Join(command.args, " "), fragment) {
			return index
		}
	}
	return -1
}

func writeActiveOwnershipState(t *testing.T, statePath string) {
	t.Helper()
	if err := writeTCState(statePath, tcOwnershipState{
		ContractVersion: tcStateContractVersion,
		WAN:             "eth0", IFB: "ifb-aneb0", IFBAlias: anebIFBAlias,
		BaselineQdisc: "qdisc noqueue 0: root refcnt 2",
	}); err != nil {
		t.Fatal(err)
	}
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
	if strings.Contains(joined, " link show ") {
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
	controller := TCController{WAN: "eth0", IFB: "ifb-aneb0", StatePath: t.TempDir() + "/tc-state.json", Executor: executor}
	profile := validProfile()
	profile.Uplink = DirectionPolicy{RateMbps: 2, DelayMs: 50, JitterMs: 10, LossPct: 1}
	profile.Downlink = DirectionPolicy{RateMbps: 5, DelayMs: 60, JitterMs: 20, LossPct: 2}
	if err := controller.Apply(context.Background(), profile); err != nil {
		t.Fatal(err)
	}
	for _, expected := range []string{
		"tc filter replace dev eth0 parent ffff:",
		"redirect dev ifb-aneb0",
		"dev eth0 root handle 1a1e: netem limit 100000 delay 50ms 10ms loss 1% rate 2mbit",
		"dev ifb-aneb0 root handle 1a1e: netem limit 100000 delay 60ms 20ms loss 2% rate 5mbit",
	} {
		if !executor.contains(expected) {
			t.Fatalf("missing command fragment %q", expected)
		}
	}
}

func TestTCControllerFailsClosedWhenCleanupStillHasNetem(t *testing.T) {
	executor := &fakeExecutor{stale: true}
	controller := TCController{WAN: "eth0", IFB: "ifb-aneb0", StatePath: t.TempDir() + "/tc-state.json", Executor: executor}
	if err := controller.Clear(context.Background()); err == nil || !strings.Contains(err.Error(), "refusing mutation") {
		t.Fatalf("cleanup error=%v", err)
	}
	if err := controller.Apply(context.Background(), validProfile()); err == nil || !strings.Contains(err.Error(), "pre-apply cleanup") {
		t.Fatalf("apply error=%v", err)
	}
}

func TestTCControllerRemovesOnlyPersistentlyOwnedResourcesAndRestoresBaseline(t *testing.T) {
	executor := &ownershipExecutor{}
	statePath := t.TempDir() + "/tc-state.json"
	controller := TCController{WAN: "eth0", IFB: "ifb-aneb0", StatePath: statePath, Executor: executor}
	if err := controller.Apply(context.Background(), validProfile()); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(statePath); err != nil {
		t.Fatalf("ownership state missing after apply: %v", err)
	}
	if err := controller.Clear(context.Background()); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(statePath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("ownership state remained after verified cleanup: %v", err)
	}
	if executor.rootOwned || executor.ingressOwned || executor.ifbExists {
		t.Fatalf("owned resources remain: %+v", executor)
	}
	ingressDelete := executor.commandIndex("qdisc del dev eth0 ingress")
	linkDown := executor.commandIndex("link set dev ifb-aneb0 down")
	linkDelete := executor.commandIndex("link del ifb-aneb0")
	if executor.contains("filter del dev eth0 parent ffff:") {
		t.Fatal("cleanup deleted the ownership filter separately from its ingress qdisc")
	}
	if !(ingressDelete >= 0 && ingressDelete < linkDown && linkDown < linkDelete) {
		t.Fatalf("unsafe cleanup order: ingress=%d down=%d delete=%d", ingressDelete, linkDown, linkDelete)
	}
}

func TestTCControllerRequiresExactIFBTypeAndAliasBeforeMutation(t *testing.T) {
	for _, test := range []struct {
		name  string
		alias string
		kind  string
	}{
		{name: "missing alias", kind: "ifb"},
		{name: "wrong alias", alias: "someone-else", kind: "ifb"},
		{name: "wrong link kind", alias: anebIFBAlias, kind: "veth"},
	} {
		t.Run(test.name, func(t *testing.T) {
			executor := &ownershipExecutor{
				rootOwned: true, ingressOwned: true, ifbExists: true,
				ifbAlias: test.alias, ifbKind: test.kind,
			}
			statePath := t.TempDir() + "/tc-state.json"
			writeActiveOwnershipState(t, statePath)
			controller := TCController{WAN: "eth0", IFB: "ifb-aneb0", StatePath: statePath, Executor: executor}
			err := controller.Clear(context.Background())
			if err == nil || !strings.Contains(err.Error(), "type=ifb") {
				t.Fatalf("cleanup error=%v", err)
			}
			if executor.contains("qdisc del dev eth0 root") || executor.contains("filter del dev eth0") || executor.contains("link del ifb-aneb0") {
				t.Fatal("controller mutated resources without exact IFB type and alias")
			}
		})
	}
}

func TestTCControllerRefusesEmptyIngressAndClsactDespiteStateFile(t *testing.T) {
	for _, test := range []struct {
		name            string
		ingress, clsact bool
		want            string
	}{
		{name: "empty ingress", ingress: true, want: "no exact ANEB-owned redirect"},
		{name: "clsact", clsact: true, want: "clsact qdisc"},
	} {
		t.Run(test.name, func(t *testing.T) {
			executor := &ownershipExecutor{
				rootOwned: true, ingressOwned: test.ingress, clsact: test.clsact,
				filterDeleted: true, ifbExists: true, ifbAlias: anebIFBAlias, ifbKind: "ifb",
			}
			statePath := t.TempDir() + "/tc-state.json"
			writeActiveOwnershipState(t, statePath)
			controller := TCController{WAN: "eth0", IFB: "ifb-aneb0", StatePath: statePath, Executor: executor}
			err := controller.Clear(context.Background())
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("cleanup error=%v want=%q", err, test.want)
			}
			if executor.contains("qdisc del dev eth0 root") || executor.contains("qdisc del dev eth0 ingress") || executor.contains("link del ifb-aneb0") {
				t.Fatal("controller treated state-file presence as resource ownership")
			}
		})
	}
}

func TestTCControllerCleanupFailuresNeverDeleteIFBEarly(t *testing.T) {
	for _, test := range []struct {
		name       string
		failure    string
		want       string
		mustNotRun []string
	}{
		{
			name: "ingress qdisc deletion", failure: "qdisc del dev eth0 ingress", want: "remove owned ingress qdisc",
			mustNotRun: []string{"link set dev ifb-aneb0 down", "link del ifb-aneb0"},
		},
		{
			name: "link down", failure: "link set dev ifb-aneb0 down", want: "set owned IFB down",
			mustNotRun: []string{"link del ifb-aneb0"},
		},
		{
			name: "link deletion", failure: "link del ifb-aneb0", want: "remove owned IFB",
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			executor := &ownershipExecutor{
				rootOwned: true, ingressOwned: true, ifbExists: true,
				ifbAlias: anebIFBAlias, ifbKind: "ifb",
				failures: map[string]int{test.failure: 1},
			}
			statePath := t.TempDir() + "/tc-state.json"
			writeActiveOwnershipState(t, statePath)
			controller := TCController{WAN: "eth0", IFB: "ifb-aneb0", StatePath: statePath, Executor: executor}
			err := controller.Clear(context.Background())
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("cleanup error=%v want=%q", err, test.want)
			}
			for _, fragment := range test.mustNotRun {
				if executor.contains(fragment) {
					t.Fatalf("unsafe command ran after failure: %s", fragment)
				}
			}
			if _, statErr := os.Stat(statePath); statErr != nil {
				t.Fatalf("ownership recovery state was removed after failure: %v", statErr)
			}
		})
	}
}

func TestTCControllerRetriesAfterIngressQdiscDeletionFailure(t *testing.T) {
	executor := &ownershipExecutor{
		rootOwned: true, ingressOwned: true, ifbExists: true,
		ifbAlias: anebIFBAlias, ifbKind: "ifb",
		failures: map[string]int{"qdisc del dev eth0 ingress": 1},
	}
	statePath := t.TempDir() + "/tc-state.json"
	writeActiveOwnershipState(t, statePath)
	controller := TCController{WAN: "eth0", IFB: "ifb-aneb0", StatePath: statePath, Executor: executor}

	firstErr := controller.Clear(context.Background())
	if firstErr == nil || !strings.Contains(firstErr.Error(), "remove owned ingress qdisc") {
		t.Fatalf("first cleanup error=%v", firstErr)
	}
	if !executor.ingressOwned || executor.filterDeleted || !executor.ifbExists {
		t.Fatalf("failed qdisc deletion did not preserve retry evidence: %+v", executor)
	}
	if _, err := os.Stat(statePath); err != nil {
		t.Fatalf("ownership state was removed after failed cleanup: %v", err)
	}

	if err := controller.Clear(context.Background()); err != nil {
		t.Fatalf("retry cleanup failed: %v", err)
	}
	if executor.ingressOwned || executor.ifbExists {
		t.Fatalf("retry left owned resources: %+v", executor)
	}
	if _, err := os.Stat(statePath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("retry did not remove verified ownership state: %v", err)
	}
}

func TestTCControllerRefusesForeignFilterBeforeAnyDeletion(t *testing.T) {
	executor := &ownershipExecutor{
		rootOwned: true, ingressOwned: true, ifbExists: true,
		ifbAlias: anebIFBAlias, foreignFilter: true,
	}
	statePath := t.TempDir() + "/tc-state.json"
	if err := writeTCState(statePath, tcOwnershipState{
		ContractVersion: tcStateContractVersion,
		WAN:             "eth0", IFB: "ifb-aneb0", IFBAlias: anebIFBAlias,
		BaselineQdisc: "qdisc noqueue 0: root refcnt 2",
	}); err != nil {
		t.Fatal(err)
	}
	controller := TCController{WAN: "eth0", IFB: "ifb-aneb0", StatePath: statePath, Executor: executor}
	if err := controller.Clear(context.Background()); err == nil || !strings.Contains(err.Error(), "foreign ingress filter") {
		t.Fatalf("cleanup error=%v", err)
	}
	if executor.contains("qdisc del dev eth0 root") || executor.contains("link del ifb-aneb0") {
		t.Fatal("controller mutated resources after detecting foreign ownership")
	}
}

func TestValidateOwnedIngressFilterRequiresExactSingleRedirect(t *testing.T) {
	owned := "filter protocol all pref 49152 u32 chain 0\nfilter protocol all pref 49152 u32 chain 0 fh 800: ht divisor 1\nfilter protocol all pref 49152 u32 chain 0 fh 800::800 order 2048 key ht 800 bkt 0 terminal flowid ??? not_in_hw\n  match 00000000/00000000 at 0\n\taction order 1: mirred (Egress Redirect to device ifb-aneb0) stolen\n\tindex 1 ref 1 bind 1"
	if err := validateOwnedIngressFilter(owned, "ifb-aneb0"); err != nil {
		t.Fatalf("owned filter rejected: %v", err)
	}
	for name, value := range map[string]string{
		"drop action":  "filter protocol all pref 49152 u32 chain 0\nfilter protocol all pref 49152 u32 chain 0 fh 800::800 order 2048 key ht 800 bkt 0 terminal\n  match 00000000/00000000 at 0\n\taction order 1: gact action drop",
		"wrong device": strings.ReplaceAll(owned, "ifb-aneb0", "ifb-other"),
		"wrong pref":   strings.ReplaceAll(owned, "pref 49152", "pref 100"),
		"extra match":  strings.Replace(owned, "match 00000000/00000000 at 0", "match 00000000/00000000 at 0\n  match 00000001/ffffffff at 0", 1),
		"extra action": owned + "\n\taction order 2: gact action drop",
	} {
		t.Run(name, func(t *testing.T) {
			if err := validateOwnedIngressFilter(value, "ifb-aneb0"); err == nil {
				t.Fatal("foreign filter was accepted")
			}
		})
	}
}

func TestTCControllerRestoresRecordedFQInsteadOfKernelDefault(t *testing.T) {
	executor := &ownershipExecutor{
		baselineQdisc: "qdisc fq 8001: root refcnt 2 limit 10000p flow_limit 100p buckets 1024 orphan_mask 1023 quantum 3028b initial_quantum 15140b low_rate_threshold 550Kbit refill_delay 40ms timer_slack 10us horizon 10s horizon_drop",
	}
	statePath := t.TempDir() + "/tc-state.json"
	controller := TCController{WAN: "eth0", IFB: "ifb-aneb0", StatePath: statePath, Executor: executor}
	if err := controller.Apply(context.Background(), validProfile()); err != nil {
		t.Fatal(err)
	}
	if err := controller.Clear(context.Background()); err != nil {
		t.Fatal(err)
	}
	if !executor.contains("qdisc replace dev eth0 root fq limit 10000") {
		t.Fatal("recorded fq baseline was not explicitly reconstructed")
	}
	if executor.contains("qdisc del dev eth0 root") {
		t.Fatal("fq cleanup relied on the machine default qdisc")
	}
}

func TestTCControllerRevalidatesTopologyBeforeMutation(t *testing.T) {
	executor := &ownershipExecutor{}
	statePath := t.TempDir() + "/tc-state.json"
	checks := 0
	controller := TCController{
		WAN: "eth0", IFB: "ifb-aneb0", StatePath: statePath, Executor: executor,
		PreApplyCheck: func(context.Context) error {
			checks++
			return errors.New("default route changed")
		},
	}
	if err := controller.Apply(context.Background(), validProfile()); err == nil || !strings.Contains(err.Error(), "pre-apply topology check") {
		t.Fatalf("apply error=%v", err)
	}
	if checks != 1 {
		t.Fatalf("topology checks=%d", checks)
	}
	if executor.contains("link add ifb-aneb0") || executor.contains("qdisc replace dev eth0 root handle") {
		t.Fatal("traffic control mutated after topology rejection")
	}
	if _, err := os.Stat(statePath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("ownership state was written after topology rejection: %v", err)
	}
}
