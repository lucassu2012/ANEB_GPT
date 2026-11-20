//go:build windows && amd64

package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
	"unsafe"

	condition "aneb-phone-forward-condition"
	"aneb-phone-forward-condition/internal/supervisor"
)

type stubNetTableReader struct {
	mu           sync.Mutex
	calls        int
	forwardingV4 map[uint32]bool
	forwardingV6 map[uint32]bool
	fwdErr       error
	routes       map[string]RouteProof
	routeErr     error
	snaps        map[uint32][]InterfaceUnicastSnapshot
	snapErr      map[uint32]error
}

func (s *stubNetTableReader) IPForwardingEnabled(interfaceIndex uint32, ipv6 bool) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.calls++
	if s.fwdErr != nil {
		return false, s.fwdErr
	}
	if ipv6 {
		return s.forwardingV6[interfaceIndex], nil
	}
	return s.forwardingV4[interfaceIndex], nil
}

func (s *stubNetTableReader) BestRoute(remote netip.Addr) (RouteProof, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.calls++
	if s.routeErr != nil {
		return RouteProof{}, s.routeErr
	}
	rp, ok := s.routes[remote.Unmap().String()]
	if !ok {
		return RouteProof{}, errors.New("unexpected probe address: " + remote.String())
	}
	return rp, nil
}

func (s *stubNetTableReader) InterfaceUnicast(interfaceIndex uint32, _ bool) (InterfaceUnicastSnapshot, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.calls++
	if err := s.snapErr[interfaceIndex]; err != nil {
		return InterfaceUnicastSnapshot{}, err
	}
	queue := s.snaps[interfaceIndex]
	if len(queue) == 0 {
		return InterfaceUnicastSnapshot{}, errors.New("missing stub snapshot")
	}
	head := queue[0]
	if len(queue) > 1 {
		s.snaps[interfaceIndex] = queue[1:]
	}
	return head, nil
}

func TestWorkerLegacyRequestOmitsUplinkProofWithoutCallingNetReader(t *testing.T) {
	root := t.TempDir()
	request := condition.Request{
		Format:                 "phone-forward-condition-1",
		Mode:                   condition.Inspect,
		PhoneAddresses:         []string{"192.0.2.8"},
		InterfaceIndex:         12,
		TTLSeconds:             1,
		DownlinkBytesPerSecond: 125000,
		DriverDirectory:        root,
		OutputDirectory:        root,
	}
	data, _ := json.Marshal(request)
	hash := sha256.Sum256(data)
	link := &onePacketWorkerLink{stop: make(chan struct{}), read: true}
	link.once.Do(func() { close(link.stop) })
	stub := &stubNetTableReader{}

	err := workerRequestWithNet(data, hex.EncodeToString(hash[:]), condition.Inspect, func() error { return nil }, condition.SystemClock{}, func(condition.Plan, condition.Mode, string) (condition.Link, error) {
		return link, nil
	}, stub)
	if err != nil {
		t.Fatalf("legacy worker failed: %v", err)
	}
	if stub.calls != 0 {
		t.Fatalf("legacy request unexpectedly queried net table %d times", stub.calls)
	}
	for _, name := range []string{"native-ready.json", "worker.json"} {
		raw, readErr := os.ReadFile(filepath.Join(root, name))
		if readErr != nil {
			t.Fatal(readErr)
		}
		if strings.Contains(string(raw), "uplink_proof") {
			t.Fatalf("legacy %s unexpectedly included uplink_proof: %s", name, raw)
		}
	}
}

func TestWorkerUplinkProofPublishesReadyAndComputesOpenCloseDelta(t *testing.T) {
	root := t.TempDir()
	request := condition.Request{
		Format:                       "phone-forward-condition-1",
		Mode:                         condition.Limit,
		PhoneAddresses:               []string{"192.168.137.42"},
		InterfaceIndex:               14,
		TTLSeconds:                   1,
		DownlinkBytesPerSecond:       125000,
		DriverDirectory:              root,
		OutputDirectory:              root,
		ExpectedUplinkInterfaceIndex: 10,
		ProbeRemoteAddresses:         []string{"198.51.100.20", "203.0.113.5"},
	}
	data, _ := json.Marshal(request)
	hash := sha256.Sum256(data)
	link := &onePacketWorkerLink{stop: make(chan struct{}), read: true}
	link.once.Do(func() { close(link.stop) })

	stub := &stubNetTableReader{
		forwardingV4: map[uint32]bool{14: true},
		routes: map[string]RouteProof{
			"198.51.100.20": {RemoteAddress: "198.51.100.20", BestInterfaceIndex: 10, NextHop: "192.168.1.1"},
			"203.0.113.5":   {RemoteAddress: "203.0.113.5", BestInterfaceIndex: 10, NextHop: "192.168.1.1"},
		},
		snaps: map[uint32][]InterfaceUnicastSnapshot{
			10: {
				{InterfaceIndex: 10, InUcastOctets: 1000, InUcastPkts: 10, OutUcastOctets: 500, OutUcastPkts: 5},
				{InterfaceIndex: 10, InUcastOctets: 2098152, InUcastPkts: 1460, OutUcastOctets: 48500, OutUcastPkts: 705},
			},
			14: {
				{InterfaceIndex: 14, InUcastOctets: 200, InUcastPkts: 2, OutUcastOctets: 300, OutUcastPkts: 3},
				{InterfaceIndex: 14, InUcastOctets: 48200, InUcastPkts: 702, OutUcastOctets: 2097452, OutUcastPkts: 1453},
			},
		},
	}

	if err := workerRequestWithNet(data, hex.EncodeToString(hash[:]), condition.Limit, func() error { return nil }, condition.SystemClock{}, func(condition.Plan, condition.Mode, string) (condition.Link, error) {
		return link, nil
	}, stub); err != nil {
		t.Fatalf("uplink-verified worker failed: %v", err)
	}

	readyBytes, err := os.ReadFile(filepath.Join(root, "native-ready.json"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(readyBytes), "uplink_close") || strings.Contains(string(readyBytes), "uplink_delta") {
		t.Fatalf("native-ready.json leaked close/delta fields: %s", readyBytes)
	}
	var ready struct {
		UplinkProof *UplinkProof `json:"uplink_proof"`
	}
	if err := json.Unmarshal(readyBytes, &ready); err != nil {
		t.Fatal(err)
	}
	if ready.UplinkProof == nil || !ready.UplinkProof.APForwardingEnabled || ready.UplinkProof.APInterfaceIndex != 14 || ready.UplinkProof.ExpectedUplinkInterfaceIndex != 10 || len(ready.UplinkProof.Routes) != 2 || ready.UplinkProof.UplinkOpen.InUcastOctets != 1000 || ready.UplinkProof.APOpen.OutUcastOctets != 300 {
		t.Fatalf("unexpected native-ready uplink_proof: %+v", ready.UplinkProof)
	}

	workerBytes, err := os.ReadFile(filepath.Join(root, "worker.json"))
	if err != nil {
		t.Fatal(err)
	}
	var outcome inspectionOutcome
	if err := json.Unmarshal(workerBytes, &outcome); err != nil {
		t.Fatal(err)
	}
	if err := validateCompletedUplinkProof(request, outcome.UplinkProof); err != nil {
		t.Fatalf("completed uplink_proof failed validation: %v", err)
	}
	if outcome.UplinkProof.UplinkDelta.InUcastOctets != 2097152 || outcome.UplinkProof.UplinkDelta.InUcastPkts != 1450 || outcome.UplinkProof.APDelta.OutUcastOctets != 2097152 || outcome.UplinkProof.APDelta.OutUcastPkts != 1450 {
		t.Fatalf("unexpected unicast deltas: uplink=%+v ap=%+v", outcome.UplinkProof.UplinkDelta, outcome.UplinkProof.APDelta)
	}
}

func TestWorkerUplinkMismatchOrDisabledForwardingBlocksReadinessAndDrainsOwnedLink(t *testing.T) {
	cases := []struct {
		name    string
		build   func() *stubNetTableReader
		wantSub string
	}{
		{
			name: "ap-forwarding-disabled",
			build: func() *stubNetTableReader {
				return &stubNetTableReader{forwardingV4: map[uint32]bool{14: false}}
			},
			wantSub: "forwarding is disabled",
		},
		{
			name: "route-bypass-to-wlan",
			build: func() *stubNetTableReader {
				return &stubNetTableReader{
					forwardingV4: map[uint32]bool{14: true},
					routes: map[string]RouteProof{
						"198.51.100.20": {RemoteAddress: "198.51.100.20", BestInterfaceIndex: 4, NextHop: "192.168.50.1"},
					},
				}
			},
			wantSub: "resolved to interface 4, expected uplink interface 10",
		},
		{
			name: "uplink-interface-down",
			build: func() *stubNetTableReader {
				return &stubNetTableReader{
					forwardingV4: map[uint32]bool{14: true},
					routes: map[string]RouteProof{
						"198.51.100.20": {RemoteAddress: "198.51.100.20", BestInterfaceIndex: 10},
					},
					snapErr: map[uint32]error{10: errors.New("interface 10 is not operational Up (oper_status=2)")},
				}
			},
			wantSub: "not operational Up",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			root := t.TempDir()
			request := condition.Request{
				Format:                       "phone-forward-condition-1",
				Mode:                         condition.Limit,
				PhoneAddresses:               []string{"192.168.137.42"},
				InterfaceIndex:               14,
				TTLSeconds:                   10,
				DownlinkBytesPerSecond:       125000,
				DriverDirectory:              root,
				OutputDirectory:              root,
				ExpectedUplinkInterfaceIndex: 10,
				ProbeRemoteAddresses:         []string{"198.51.100.20"},
			}
			data, _ := json.Marshal(request)
			hash := sha256.Sum256(data)
			link := &onePacketWorkerLink{stop: make(chan struct{}), read: true}
			link.once.Do(func() { close(link.stop) })

			err := workerRequestWithNet(data, hex.EncodeToString(hash[:]), condition.Limit, func() error { return nil }, condition.SystemClock{}, func(condition.Plan, condition.Mode, string) (condition.Link, error) {
				return link, nil
			}, tc.build())
			if err == nil || !strings.Contains(err.Error(), tc.wantSub) {
				t.Fatalf("expected error containing %q, got %v", tc.wantSub, err)
			}
			if _, statErr := os.Stat(filepath.Join(root, "native-ready.json")); !os.IsNotExist(statErr) {
				t.Fatal("failed uplink check still published native-ready.json")
			}
			if !link.closed.Load() {
				t.Fatal("owned WinDivert link was not closed after uplink refusal")
			}
			workerBytes, readErr := os.ReadFile(filepath.Join(root, "worker.json"))
			if readErr != nil {
				t.Fatal(readErr)
			}
			var outcome inspectionOutcome
			if err := json.Unmarshal(workerBytes, &outcome); err != nil {
				t.Fatal(err)
			}
			if !strings.Contains(outcome.Failure, tc.wantSub) || !outcome.Receipt.DriverDrained || !outcome.Receipt.OwnedHandleClosed || !outcome.Receipt.WorkersJoined {
				t.Fatalf("worker outcome did not retain clean shutdown failure receipt: %+v", outcome)
			}
		})
	}
}

func TestWorkerDetectsUnicastCounterRollbackOnClose(t *testing.T) {
	root := t.TempDir()
	request := condition.Request{
		Format:                       "phone-forward-condition-1",
		Mode:                         condition.Inspect,
		PhoneAddresses:               []string{"192.168.137.42"},
		InterfaceIndex:               14,
		TTLSeconds:                   1,
		DownlinkBytesPerSecond:       125000,
		DriverDirectory:              root,
		OutputDirectory:              root,
		ExpectedUplinkInterfaceIndex: 10,
	}
	data, _ := json.Marshal(request)
	hash := sha256.Sum256(data)
	link := &onePacketWorkerLink{stop: make(chan struct{}), read: true}
	link.once.Do(func() { close(link.stop) })

	stub := &stubNetTableReader{
		forwardingV4: map[uint32]bool{14: true},
		snaps: map[uint32][]InterfaceUnicastSnapshot{
			10: {
				{InterfaceIndex: 10, InUcastOctets: 5000, InUcastPkts: 50},
				{InterfaceIndex: 10, InUcastOctets: 4000, InUcastPkts: 40}, // rollback
			},
			14: {
				{InterfaceIndex: 14, OutUcastOctets: 100, OutUcastPkts: 1},
				{InterfaceIndex: 14, OutUcastOctets: 200, OutUcastPkts: 2},
			},
		},
	}

	err := workerRequestWithNet(data, hex.EncodeToString(hash[:]), condition.Inspect, func() error { return nil }, condition.SystemClock{}, func(condition.Plan, condition.Mode, string) (condition.Link, error) {
		return link, nil
	}, stub)
	if err == nil || !strings.Contains(err.Error(), "rollback/reset") {
		t.Fatalf("expected counter rollback error, got %v", err)
	}
}

func TestParentRejectsMissingWorkerUplinkProofWhenConfigured(t *testing.T) {
	root := t.TempDir()
	request := condition.Request{
		Format:                       "phone-forward-condition-1",
		Mode:                         condition.Inspect,
		PhoneAddresses:               []string{"192.168.137.42"},
		InterfaceIndex:               14,
		TTLSeconds:                   1,
		DownlinkBytesPerSecond:       125000,
		DriverDirectory:              root,
		OutputDirectory:              filepath.Join(root, "supervised-out"),
		ExpectedUplinkInterfaceIndex: 10,
		ProbeRemoteAddresses:         []string{"198.51.100.20"},
	}
	data, _ := json.Marshal(request)
	var output bytes.Buffer
	err := inspectRequest(data, &output, func() error { return nil }, func(_ string, args []string, _ time.Duration) (supervisor.ProcessResult, error) {
		_ = writeNewJSON(filepath.Join(request.OutputDirectory, "worker.json"), inspectionOutcome{
			Format:              "phone-forward-inspect-result-1",
			RequestSHA256:       args[4],
			PID:                 321,
			DriverOpenAttempted: true,
			OwnedHandleReturned: true,
			Receipt:             condition.Receipt{Mode: condition.Inspect, State: "NO_MATCHING_PACKETS", StopReason: "TTL_EXPIRED", DriverDrained: true, OwnedHandleClosed: true, WorkersJoined: true},
		})
		code := uint32(0)
		return supervisor.ProcessResult{PID: 321, ExitCode: &code, ExitConfirmed: true, AssignedToOwnedJob: true, DeadlineArmedBeforeResume: true, Resumed: true, OwnedTreeEmpty: true, JobClosed: true, ProcessHandleClosed: true, ThreadHandleClosed: true, TimerHandleClosed: true}, nil
	})
	if err == nil || !strings.Contains(err.Error(), "uplink_proof") {
		t.Fatalf("parent accepted missing worker uplink_proof: %v", err)
	}
}

func TestIphlpapiReadOnlyStructSizesAndLoopbackSmoke(t *testing.T) {
	if unsafe.Sizeof(sockaddrInet{}) != 28 || unsafe.Sizeof(ipAddressPrefix{}) != 32 || unsafe.Sizeof(mibIPForwardRow2{}) != 104 || unsafe.Sizeof(mibIPInterfaceRow{}) != 168 || unsafe.Sizeof(mibIfRow2{}) != 1352 {
		t.Fatal("unexpected Windows x64 iphlpapi ABI struct size")
	}
	reader := iphlpapiReader{}
	if _, err := reader.IPForwardingEnabled(1, false); err != nil {
		t.Fatalf("read-only GetIpInterfaceEntry on loopback failed: %v", err)
	}
	snap, err := reader.InterfaceUnicast(1, true)
	if err != nil || snap.InterfaceIndex != 1 {
		t.Fatalf("read-only GetIfEntry2 on loopback failed: snap=%+v err=%v", snap, err)
	}
	route, err := reader.BestRoute(netip.MustParseAddr("127.0.0.1"))
	if err != nil || route.BestInterfaceIndex != 1 || route.RemoteAddress != "127.0.0.1" {
		t.Fatalf("read-only GetBestRoute2 to loopback failed: route=%+v err=%v", route, err)
	}
}
