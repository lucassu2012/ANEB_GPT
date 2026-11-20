package condition

import (
	"encoding/json"
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
