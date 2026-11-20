package condition

import (
	"encoding/json"
	"math"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRequestProducesBoundedPlanWithoutOpeningMissingDriverDirectory(t *testing.T) {
	root := t.TempDir()
	want := Request{Format: "phone-forward-condition-1", Mode: Inspect, PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTLSeconds: 30, DownlinkBytesPerSecond: 125000, DriverDirectory: filepath.Join(root, "driver-not-created"), OutputDirectory: filepath.Join(root, "run-not-created")}
	data, _ := json.Marshal(want)
	if strings.Contains(string(data), "expected_uplink_interface_index") || strings.Contains(string(data), "probe_remote_addresses") ||
		strings.Contains(string(data), "queue_overflow_policy") || strings.Contains(string(data), "queue_limit_bytes") {
		t.Fatalf("optional fields leaked into legacy marshaled request: %s", data)
	}
	got, plan, err := ParseRequest(data)
	if err != nil {
		t.Fatal(err)
	}
	if got.Mode != Inspect || got.DriverDirectory != want.DriverDirectory || got.ExpectedUplinkInterfaceIndex != 0 || len(got.ProbeRemoteAddresses) != 0 || plan.Config.TTL != 30*time.Second || plan.Filter != "ifIdx == 12 and (ip.DstAddr == 192.0.2.8)" {
		t.Fatalf("wrong finite plan: %+v %+v", got, plan)
	}
}

func TestRequestValidatesOptionalUplinkAndProbeRemoteAddresses(t *testing.T) {
	root := t.TempDir()
	valid := Request{
		Format:                       "phone-forward-condition-1",
		Mode:                         Limit,
		PhoneAddresses:               []string{"192.168.137.42"},
		InterfaceIndex:               14,
		TTLSeconds:                   35,
		DownlinkBytesPerSecond:       125000,
		DriverDirectory:              filepath.Join(root, "driver"),
		OutputDirectory:              filepath.Join(root, "out"),
		ExpectedUplinkInterfaceIndex: 10,
		ProbeRemoteAddresses:         []string{"::ffff:198.51.100.20", "2001:db8::9"},
	}
	data, err := json.Marshal(valid)
	if err != nil {
		t.Fatal(err)
	}
	got, _, err := ParseRequest(data)
	if err != nil {
		t.Fatalf("valid uplink request rejected: %v", err)
	}
	if got.ExpectedUplinkInterfaceIndex != 10 || len(got.ProbeRemoteAddresses) != 2 || got.ProbeRemoteAddresses[0] != "198.51.100.20" || got.ProbeRemoteAddresses[1] != "2001:db8::9" {
		t.Fatalf("probe addresses not normalized: %+v", got)
	}

	cases := []struct {
		name   string
		mutate func(*Request)
	}{
		{"remotes-without-uplink", func(r *Request) {
			r.ExpectedUplinkInterfaceIndex = 0
			r.ProbeRemoteAddresses = []string{"198.51.100.20"}
		}},
		{"uplink-equals-forwarding-if", func(r *Request) {
			r.ExpectedUplinkInterfaceIndex = r.InterfaceIndex
			r.ProbeRemoteAddresses = []string{"198.51.100.20"}
		}},
		{"loopback-probe-remote", func(r *Request) {
			r.ProbeRemoteAddresses = []string{"127.0.0.1"}
		}},
		{"cidr-probe-remote", func(r *Request) {
			r.ProbeRemoteAddresses = []string{"198.51.100.0/24"}
		}},
		{"too-many-probe-remotes", func(r *Request) {
			r.ProbeRemoteAddresses = make([]string, 17)
			for i := range r.ProbeRemoteAddresses {
				r.ProbeRemoteAddresses[i] = "198.51.100.20"
			}
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			bad := valid
			tc.mutate(&bad)
			raw, _ := json.Marshal(bad)
			if _, _, err := ParseRequest(raw); err == nil {
				t.Fatalf("expected invalid uplink request %s to fail", tc.name)
			}
		})
	}
}

func TestRequestRoundTripsQueueOverflowPolicyAndLimitBytes(t *testing.T) {
	root := t.TempDir()
	req := Request{
		Format:                 "phone-forward-condition-1",
		Mode:                   Limit,
		PhoneAddresses:         []string{"192.168.137.42"},
		InterfaceIndex:         14,
		TTLSeconds:             35,
		DownlinkBytesPerSecond: 125000,
		QueueOverflowPolicy:    QueueTailDrop,
		QueueLimitBytes:        64 << 10,
		DriverDirectory:        filepath.Join(root, "driver"),
		OutputDirectory:        filepath.Join(root, "out"),
	}
	data, err := json.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}
	got, plan, err := ParseRequest(data)
	if err != nil {
		t.Fatalf("valid queue policy request rejected: %v", err)
	}
	if got.QueueOverflowPolicy != QueueTailDrop || got.QueueLimitBytes != 64<<10 ||
		plan.Config.QueueOverflowPolicy != QueueTailDrop || plan.Config.QueueLimitBytes != 64<<10 {
		t.Fatalf("queue config not preserved: req=%+v plan=%+v", got, plan.Config)
	}
}

func TestRequestValidatesBidirectionalUplinkFieldsAndRejectsUnknownKeys(t *testing.T) {
	root := t.TempDir()
	base := Request{
		Format:                       "phone-forward-condition-1",
		Mode:                         Limit,
		PhoneAddresses:               []string{"192.168.137.113"},
		InterfaceIndex:               16,
		TTLSeconds:                   35,
		DownlinkBytesPerSecond:       250000,
		DriverDirectory:              filepath.Join(root, "driver"),
		OutputDirectory:              filepath.Join(root, "out"),
		ExpectedUplinkInterfaceIndex: 12,
		ProbeRemoteAddresses:         []string{"120.77.174.207"},
	}
	legacyBytes, _ := json.Marshal(base)
	for _, forbidden := range []string{"uplink_ip_bytes_per_second", "uplink_delay_ms", "uplink_loss_bps", "uplink_impairment_seed"} {
		if strings.Contains(string(legacyBytes), forbidden) {
			t.Fatalf("unset bidirectional field %s leaked into downlink-only JSON: %s", forbidden, legacyBytes)
		}
	}

	bidir := base
	bidir.UplinkBytesPerSecond = 62500
	bidir.UplinkDelayMs = 45
	bidir.UplinkLossBps = 150
	bidir.UplinkImpairmentSeed = 20261120
	raw, err := json.Marshal(bidir)
	if err != nil {
		t.Fatal(err)
	}
	got, plan, err := ParseRequest(raw)
	if err != nil {
		t.Fatalf("valid bidirectional request rejected: %v", err)
	}
	wantFilter := "(ifIdx == 16 and (ip.DstAddr == 192.168.137.113)) or (ifIdx == 12 and (ip.SrcAddr == 192.168.137.113))"
	if plan.Filter != wantFilter {
		t.Fatalf("filter = %q; want %q", plan.Filter, wantFilter)
	}
	if !plan.Config.HasUplink() || plan.Config.UplinkInterfaceIndex != 12 ||
		got.UplinkBytesPerSecond != 62500 || plan.Config.UplinkBytesPerSecond != 62500 ||
		got.UplinkDelayMs != 45 || plan.Config.UplinkDelay != 45*time.Millisecond ||
		got.UplinkLossBps != 150 || plan.Config.UplinkLossBasisPoints != 150 ||
		got.UplinkImpairmentSeed != 20261120 || plan.Config.UplinkImpairmentSeed != 20261120 {
		t.Fatalf("bidirectional fields lost: got=%+v cfg=%+v", got, plan.Config)
	}

	cases := []struct {
		name   string
		mutate func(*Request)
	}{
		{"uplink-delay-without-rate", func(r *Request) { r.UplinkDelayMs = 10 }},
		{"uplink-loss-without-rate", func(r *Request) { r.UplinkLossBps = 50; r.UplinkImpairmentSeed = 1 }},
		{"uplink-seed-without-rate", func(r *Request) { r.UplinkImpairmentSeed = 1 }},
		{"uplink-loss-without-seed", func(r *Request) { r.UplinkBytesPerSecond = 62500; r.UplinkLossBps = 50 }},
		{"uplink-without-expected-if", func(r *Request) {
			r.UplinkBytesPerSecond = 62500
			r.ExpectedUplinkInterfaceIndex = 0
			r.ProbeRemoteAddresses = nil
		}},
		{"uplink-rate-too-high", func(r *Request) { r.UplinkBytesPerSecond = 1_000_000_001 }},
		{"uplink-delay-too-high", func(r *Request) { r.UplinkBytesPerSecond = 62500; r.UplinkDelayMs = 2001 }},
		{"uplink-loss-too-high", func(r *Request) { r.UplinkBytesPerSecond = 62500; r.UplinkLossBps = 5001; r.UplinkImpairmentSeed = 1 }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			bad := base
			tc.mutate(&bad)
			b, _ := json.Marshal(bad)
			if _, _, err := ParseRequest(b); err == nil {
				t.Fatalf("expected invalid bidirectional request %s to fail", tc.name)
			}
		})
	}

	unknownJSON := strings.Replace(string(raw), `"uplink_delay_ms":45`, `"uplink_delay_ms":45,"unknown_field":1`, 1)
	if _, _, err := ParseRequest([]byte(unknownJSON)); err == nil {
		t.Fatal("DisallowUnknownFields failed to reject unknown key")
	}

	natReq := base
	natReq.UplinkBytesPerSecond = 62500
	natReq.UplinkNatAddresses = []string{"10.10.8.9"}
	natRaw, _ := json.Marshal(natReq)
	gotNat, natPlan, err := ParseRequest(natRaw)
	if err != nil {
		t.Fatalf("ParseRequest rejected valid uplink_nat_addresses: %v", err)
	}
	if len(gotNat.UplinkNatAddresses) != 1 || gotNat.UplinkNatAddresses[0] != "10.10.8.9" ||
		!strings.Contains(natPlan.Filter, "ip.SrcAddr == 10.10.8.9") {
		t.Fatalf("unexpected NAT plan: got=%+v filter=%q", gotNat.UplinkNatAddresses, natPlan.Filter)
	}
	badNat := base
	badNat.UplinkNatAddresses = []string{"10.10.8.9"}
	badNatRaw, _ := json.Marshal(badNat)
	if _, _, err := ParseRequest(badNatRaw); err == nil {
		t.Fatal("expected uplink_nat_addresses without uplink rate to fail")
	}
}

func TestRequestRejectsDelayUint64OverflowWraparoundAndRespects2000msBound(t *testing.T) {
	root := t.TempDir()
	base := Request{
		Format:                       "phone-forward-condition-1",
		Mode:                         Limit,
		PhoneAddresses:               []string{"192.168.137.113"},
		InterfaceIndex:               16,
		TTLSeconds:                   35,
		DownlinkBytesPerSecond:       250000,
		UplinkBytesPerSecond:         62500,
		ExpectedUplinkInterfaceIndex: 12,
		DriverDirectory:              filepath.Join(root, "driver"),
		OutputDirectory:              filepath.Join(root, "out"),
	}

	okMax := base
	okMax.DownlinkDelayMs = 2000
	okMax.UplinkDelayMs = 2000
	okBytes, err := json.Marshal(okMax)
	if err != nil {
		t.Fatal(err)
	}
	_, okPlan, err := ParseRequest(okBytes)
	if err != nil {
		t.Fatalf("expected 2000ms delay boundary to succeed: %v", err)
	}
	if okPlan.Config.DownlinkDelay != 2000*time.Millisecond || okPlan.Config.UplinkDelay != 2000*time.Millisecond {
		t.Fatalf("unexpected 2000ms boundary plan delays: dl=%v ul=%v", okPlan.Config.DownlinkDelay, okPlan.Config.UplinkDelay)
	}

	cases := []struct {
		name   string
		mutate func(*Request)
	}{
		{"downlink-2001ms", func(r *Request) { r.DownlinkDelayMs = 2001 }},
		{"uplink-2001ms", func(r *Request) { r.UplinkDelayMs = 2001 }},
		{"downlink-wraparound-18446744073710", func(r *Request) { r.DownlinkDelayMs = 18446744073710 }},
		{"uplink-wraparound-18446744073710", func(r *Request) { r.UplinkDelayMs = 18446744073710 }},
		{"uplink-zero-wrap-no-rate", func(r *Request) {
			r.UplinkBytesPerSecond = 0
			r.ExpectedUplinkInterfaceIndex = 0
			r.UplinkDelayMs = 288230376151711744 // 2^58 * 1e6 == 0 mod 2^64
		}},
		{"downlink-max-uint64", func(r *Request) { r.DownlinkDelayMs = math.MaxUint64 }},
		{"uplink-max-uint64", func(r *Request) { r.UplinkDelayMs = math.MaxUint64 }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			bad := base
			tc.mutate(&bad)
			raw, err := json.Marshal(bad)
			if err != nil {
				t.Fatal(err)
			}
			if _, plan, err := ParseRequest(raw); err == nil {
				t.Fatalf("expected invalid delay %s to fail, got plan=%+v", tc.name, plan.Config)
			}
		})
	}
}
