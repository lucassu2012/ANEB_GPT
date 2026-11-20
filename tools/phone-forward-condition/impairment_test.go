package condition

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRequestValidatesOptionalDelayAndLossFields(t *testing.T) {
	root := t.TempDir()
	base := Request{
		Format:                 "phone-forward-condition-1",
		Mode:                   Limit,
		PhoneAddresses:         []string{"192.168.137.42"},
		InterfaceIndex:         14,
		TTLSeconds:             35,
		DownlinkBytesPerSecond: 250000,
		DriverDirectory:        filepath.Join(root, "driver"),
		OutputDirectory:        filepath.Join(root, "out"),
	}
	legacyRaw, _ := json.Marshal(base)
	if strings.Contains(string(legacyRaw), "downlink_delay_ms") || strings.Contains(string(legacyRaw), "downlink_loss_bps") || strings.Contains(string(legacyRaw), "impairment_seed") {
		t.Fatalf("optional impairment fields leaked into legacy marshaled request: %s", legacyRaw)
	}

	valid := base
	valid.DownlinkDelayMs = 150
	valid.DownlinkLossBps = 250
	valid.ImpairmentSeed = 42
	raw, _ := json.Marshal(valid)
	got, plan, err := ParseRequest(raw)
	if err != nil {
		t.Fatalf("valid impairment request rejected: %v", err)
	}
	if got.DownlinkDelayMs != 150 || got.DownlinkLossBps != 250 || got.ImpairmentSeed != 42 ||
		plan.Config.DownlinkDelay != 150*time.Millisecond || plan.Config.DownlinkLossBasisPoints != 250 || plan.Config.ImpairmentSeed != 42 {
		t.Fatalf("impairment fields not preserved: %+v %+v", got, plan.Config)
	}

	cases := []struct {
		name   string
		mutate func(*Request)
	}{
		{"delay-too-high", func(r *Request) { r.DownlinkDelayMs = 2001 }},
		{"loss-too-high", func(r *Request) { r.DownlinkLossBps = 5001; r.ImpairmentSeed = 1 }},
		{"loss-without-seed", func(r *Request) { r.DownlinkLossBps = 100; r.ImpairmentSeed = 0 }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			bad := base
			tc.mutate(&bad)
			b, _ := json.Marshal(bad)
			if _, _, err := ParseRequest(b); err == nil {
				t.Fatalf("expected invalid impairment request %s to fail", tc.name)
			}
		})
	}
}

func TestPacerDelayPipePreservesThroughputAndAddsPropagationDelay(t *testing.T) {
	clock := &manualClock{now: time.Unix(0, 0)}
	// 250,000 B/s (2 Mbps) + 100 ms propagation delay.
	// Three 1,250 B packets (5 ms serialization each) arriving simultaneously at t=0
	// should inject at 105 ms, 110 ms, 115 ms (NOT 105, 210, 315 ms!).
	p, err := NewPacer(Config{
		PhoneAddresses:         []string{"192.0.2.8"},
		InterfaceIndex:         12,
		TTL:                    2 * time.Second,
		DownlinkBytesPerSecond: 250000,
		DownlinkDelay:          100 * time.Millisecond,
	}, clock)
	if err != nil {
		t.Fatal(err)
	}
	packet := bytes.Repeat([]byte{0x42}, 1250)
	arrivedAt := time.Unix(0, 0)
	var actual []time.Duration
	for range 3 {
		if _, err := p.InjectAt(context.Background(), arrivedAt, packet, func([]byte) error {
			actual = append(actual, clock.Now().Sub(time.Unix(0, 0)))
			return nil
		}); err != nil {
			t.Fatal(err)
		}
	}
	want := []time.Duration{105 * time.Millisecond, 110 * time.Millisecond, 115 * time.Millisecond}
	for i := range want {
		if actual[i] != want[i] {
			t.Fatalf("burst injection times=%v, want=%v", actual, want)
		}
	}
	// A packet arriving later at t=500ms should inject at 500 + 5 + 100 = 605 ms.
	clock.now = time.Unix(0, 0).Add(500 * time.Millisecond)
	if _, err := p.InjectAt(context.Background(), clock.now, packet, func([]byte) error {
		if got := clock.Now().Sub(time.Unix(0, 0)); got != 605*time.Millisecond {
			t.Fatalf("idle-resume injection time=%v, want=605ms", got)
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}

func TestEngineDeterministicLossMaintainsExactPacketConservation(t *testing.T) {
	makeFrames := func(n int) []Frame {
		out := make([]Frame, n)
		for i := range n {
			out[i] = testForwardFrame(100)
		}
		return out
	}
	runOnce := func() Receipt {
		link := &memoryLink{frames: makeFrames(200)}
		receipt, err := Run(
			context.Background(),
			Config{
				PhoneAddresses:          []string{"192.0.2.8"},
				InterfaceIndex:          12,
				TTL:                     5 * time.Second,
				DownlinkBytesPerSecond:  10_000_000,
				DownlinkLossBasisPoints: 1000, // 10%
				ImpairmentSeed:          20261120,
			},
			Limit,
			SystemClock{},
			func(Plan, Mode) (Link, error) { return link, nil },
		)
		if err != nil {
			t.Fatalf("unexpected error: %v receipt=%+v", err, receipt)
		}
		if receipt.OwnedReceivedPackets != 200 || receipt.UnconfirmedUserPackets != 0 ||
			receipt.InjectedPackets+receipt.DroppedPackets+receipt.CleanupInjectedPackets != 200 {
			t.Fatalf("packet conservation violated: %+v", receipt)
		}
		if receipt.DroppedPackets == 0 || receipt.InjectedPackets == 0 {
			t.Fatalf("expected mix of dropped and injected packets at 10%% loss: %+v", receipt)
		}
		if receipt.DroppedBytes != receipt.DroppedPackets*100 {
			t.Fatalf("dropped byte count mismatch: %+v", receipt)
		}
		return receipt
	}
	r1 := runOnce()
	r2 := runOnce()
	if r1.DroppedPackets != r2.DroppedPackets || r1.InjectedPackets != r2.InjectedPackets {
		t.Fatalf("deterministic seed produced non-identical drop counts: r1=%+v r2=%+v", r1, r2)
	}
}

func TestEngineTailDropPolicyPreservesValidityAndExactConservation(t *testing.T) {
	// 40 frames x 1,000 B = 40,000 B burst against a 16 KiB queue limit at 16 KB/s:
	// with QueueTailDrop, excess frames drop at queue entry without invalidating the run,
	// and exact packet conservation (UnconfirmedUserPackets == 0) still holds.
	frames := make([]Frame, 40)
	for i := range frames {
		frames[i] = testForwardFrame(1000)
	}
	link := &memoryLink{frames: frames}
	receipt, err := Run(
		context.Background(),
		Config{
			PhoneAddresses:         []string{"192.0.2.8"},
			InterfaceIndex:         12,
			TTL:                    1 * time.Second,
			DownlinkBytesPerSecond: 16_000,
			QueueOverflowPolicy:    QueueTailDrop,
			QueueLimitBytes:        16 << 10,
		},
		Limit,
		SystemClock{},
		func(Plan, Mode) (Link, error) { return link, nil },
	)
	if err != nil {
		t.Fatalf("tail_drop should not invalidate condition: %v receipt=%+v", err, receipt)
	}
	if receipt.State != "INJECTED_NOT_CALIBRATED" || receipt.QueueDroppedPackets == 0 {
		t.Fatalf("expected valid state with nonzero queue drops: %+v", receipt)
	}
	if receipt.UnconfirmedUserPackets != 0 ||
		receipt.InjectedPackets+receipt.DroppedPackets+receipt.QueueDroppedPackets+receipt.CleanupInjectedPackets != receipt.OwnedReceivedPackets {
		t.Fatalf("tail_drop conservation violated: %+v", receipt)
	}
	if receipt.QueueDroppedBytes != receipt.QueueDroppedPackets*1000 {
		t.Fatalf("queue dropped byte mismatch: %+v", receipt)
	}
}

func TestEngineInvalidatePolicyWithSmallQueueLimitFailsClosedOnBurst(t *testing.T) {
	frames := make([]Frame, 40)
	for i := range frames {
		frames[i] = testForwardFrame(1000)
	}
	link := &memoryLink{frames: frames}
	receipt, err := Run(
		context.Background(),
		Config{
			PhoneAddresses:         []string{"192.0.2.8"},
			InterfaceIndex:         12,
			TTL:                    1 * time.Second,
			DownlinkBytesPerSecond: 16_000,
			QueueOverflowPolicy:    QueueInvalidate,
			QueueLimitBytes:        16 << 10,
		},
		Limit,
		SystemClock{},
		func(Plan, Mode) (Link, error) { return link, nil },
	)
	if !errors.Is(err, ErrQueueOverflow) {
		t.Fatalf("expected ErrQueueOverflow, got err=%v receipt=%+v", err, receipt)
	}
	if receipt.StopReason != "USER_QUEUE_OVERFLOW" || receipt.UnconfirmedUserPackets != 0 || receipt.QueueDroppedPackets != 0 {
		t.Fatalf("unexpected invalidate overflow receipt: %+v", receipt)
	}
}

func TestUplinkSeedDomainSeparationAndPerDirectionTailDropIsolation(t *testing.T) {
	const seed uint64 = 20261120
	upSeed := splitMix64Next(seed ^ uplinkSeedDomain)
	same := true
	for seq := uint64(1); seq <= 64; seq++ {
		if shouldDrop(seed, seq, 5000) != shouldDrop(upSeed, seq, 5000) {
			same = false
			break
		}
	}
	if same {
		t.Fatal("uplink and downlink PRNG sequences collided under identical seeds")
	}

	frames := make([]Frame, 0, 45)
	for range 40 {
		f := testForwardFrame(1000)
		frames = append(frames, f)
	}
	for range 5 {
		frames = append(frames, testUplinkForwardFrame(100, 16, true))
	}
	link := &memoryLink{frames: frames}
	receipt, err := Run(
		context.Background(),
		Config{
			PhoneAddresses:         []string{"192.0.2.8"},
			InterfaceIndex:         12,
			UplinkInterfaceIndex:   16,
			TTL:                    2 * time.Second,
			DownlinkBytesPerSecond: 16_000,
			UplinkBytesPerSecond:   1_000_000,
			QueueOverflowPolicy:    QueueTailDrop,
			QueueLimitBytes:        16 << 10,
		},
		Limit,
		SystemClock{},
		func(Plan, Mode) (Link, error) { return link, nil },
	)
	if err != nil {
		t.Fatalf("bidirectional tail_drop failed: %v receipt=%+v", err, receipt)
	}
	if receipt.Downlink == nil || receipt.Uplink == nil {
		t.Fatalf("missing directional receipts: %+v", receipt)
	}
	if receipt.Downlink.QueueDroppedPackets == 0 || receipt.Uplink.QueueDroppedPackets != 0 || receipt.Uplink.InjectedPackets != 5 {
		t.Fatalf("per-direction tail_drop isolation failed: down=%+v up=%+v", receipt.Downlink, receipt.Uplink)
	}
	if receipt.UnconfirmedUserPackets != 0 || receipt.Downlink.UnconfirmedUserPackets != 0 || receipt.Uplink.UnconfirmedUserPackets != 0 {
		t.Fatalf("bidirectional conservation violated: %+v down=%+v up=%+v", receipt, receipt.Downlink, receipt.Uplink)
	}
}

func TestBidirectionalDeterministicLossConservesPacketsPerDirection(t *testing.T) {
	frames := make([]Frame, 0, 200)
	for range 100 {
		frames = append(frames, testForwardFrame(100))
		frames = append(frames, testUplinkForwardFrame(80, 16, true))
	}
	link := &memoryLink{frames: frames}
	receipt, err := Run(
		context.Background(),
		Config{
			PhoneAddresses:          []string{"192.0.2.8"},
			InterfaceIndex:          12,
			UplinkInterfaceIndex:    16,
			TTL:                     5 * time.Second,
			DownlinkBytesPerSecond:  10_000_000,
			DownlinkLossBasisPoints: 2000,
			ImpairmentSeed:          20261120,
			UplinkBytesPerSecond:    10_000_000,
			UplinkLossBasisPoints:   1000,
			UplinkImpairmentSeed:    20261120,
		},
		Limit,
		SystemClock{},
		func(Plan, Mode) (Link, error) { return link, nil },
	)
	if err != nil {
		t.Fatalf("bidirectional loss run failed: %v receipt=%+v", err, receipt)
	}
	if receipt.Downlink == nil || receipt.Uplink == nil {
		t.Fatalf("missing directional receipts: %+v", receipt)
	}
	if receipt.Downlink.OwnedReceivedPackets != 100 || receipt.Uplink.OwnedReceivedPackets != 100 ||
		receipt.Downlink.DroppedPackets == 0 || receipt.Uplink.DroppedPackets == 0 ||
		receipt.UnconfirmedUserPackets != 0 || receipt.Downlink.UnconfirmedUserPackets != 0 || receipt.Uplink.UnconfirmedUserPackets != 0 {
		t.Fatalf("bidirectional loss conservation mismatch: %+v down=%+v up=%+v", receipt, receipt.Downlink, receipt.Uplink)
	}
}
