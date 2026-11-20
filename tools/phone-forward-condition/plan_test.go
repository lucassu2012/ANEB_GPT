package condition

import (
	"testing"
	"time"
)

func TestPlanTargetsOnlyPhoneDestinationAndForwardingInterface(t *testing.T) {
	plan, err := NewPlan(Config{PhoneAddresses: []string{"192.0.2.8", "2001:db8::8"}, InterfaceIndex: 12, TTL: 30 * time.Second, DownlinkBytesPerSecond: 125000})
	if err != nil {
		t.Fatal(err)
	}
	want := "ifIdx == 12 and (ip.DstAddr == 192.0.2.8 or ipv6.DstAddr == 2001:db8::8)"
	if plan.Filter != want {
		t.Fatalf("filter = %q; want %q", plan.Filter, want)
	}
	if plan.Config.TTL != 30*time.Second || plan.Config.DownlinkBytesPerSecond != 125000 {
		t.Fatalf("lost finite condition: %+v", plan.Config)
	}
}

func TestPlanRejectsUnboundedOrAmbiguousCondition(t *testing.T) {
	valid := Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: 30 * time.Second, DownlinkBytesPerSecond: 125000}
	cases := map[string]func(*Config){
		"no_ttl":           func(c *Config) { c.TTL = 0 },
		"negative_ttl":     func(c *Config) { c.TTL = -time.Second },
		"too_long":         func(c *Config) { c.TTL = 121 * time.Second },
		"all_interfaces":   func(c *Config) { c.InterfaceIndex = 0 },
		"no_phone":         func(c *Config) { c.PhoneAddresses = nil },
		"filter_injection": func(c *Config) { c.PhoneAddresses = []string{"192.0.2.8 or true"} },
		"prefix_not_host":  func(c *Config) { c.PhoneAddresses = []string{"192.0.2.0/24"} },
		"unspecified":      func(c *Config) { c.PhoneAddresses = []string{"0.0.0.0"} },
		"loopback":         func(c *Config) { c.PhoneAddresses = []string{"::1"} },
		"multicast":        func(c *Config) { c.PhoneAddresses = []string{"ff02::1"} },
		"zero_rate":        func(c *Config) { c.DownlinkBytesPerSecond = 0 },
		"unbounded_rate":   func(c *Config) { c.DownlinkBytesPerSecond = 1 << 63 },
		"bad_queue_policy": func(c *Config) { c.QueueOverflowPolicy = QueueOverflowPolicy("drop_head") },
		"queue_too_small":  func(c *Config) { c.QueueLimitBytes = 1024 },
		"queue_too_large":  func(c *Config) { c.QueueLimitBytes = (4 << 20) + 1 },
	}
	for name, change := range cases {
		t.Run(name, func(t *testing.T) {
			c := valid
			change(&c)
			if _, err := NewPlan(c); err == nil {
				t.Fatal("unsafe condition accepted")
			}
		})
	}
}

func TestPlanAcceptsBoundedTailDropPolicyAndDefaultsEmptyToInvalidate(t *testing.T) {
	base := Config{PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTL: 30 * time.Second, DownlinkBytesPerSecond: 125000}
	def, err := NewPlan(base)
	if err != nil {
		t.Fatalf("default policy rejected: %v", err)
	}
	if def.Config.QueueOverflowPolicy != QueueInvalidate || def.Config.QueueLimitBytes != 0 {
		t.Fatalf("unexpected default queue config: %+v", def.Config)
	}
	for _, limit := range []uint32{0, 16 << 10, 4 << 20} {
		cfg := base
		cfg.QueueOverflowPolicy = QueueTailDrop
		cfg.QueueLimitBytes = limit
		plan, err := NewPlan(cfg)
		if err != nil {
			t.Fatalf("tail_drop with limit %d rejected: %v", limit, err)
		}
		if plan.Config.QueueOverflowPolicy != QueueTailDrop || plan.Config.QueueLimitBytes != limit {
			t.Fatalf("lost tail_drop queue config: %+v", plan.Config)
		}
	}
}

func TestPlanBuildsBidirectionalDualStackFilterAndRejectsInvalidUplinkBounds(t *testing.T) {
	cfg := Config{
		PhoneAddresses:         []string{"192.168.137.113", "2001:db8::8"},
		InterfaceIndex:         16,
		UplinkInterfaceIndex:   12,
		TTL:                    30 * time.Second,
		DownlinkBytesPerSecond: 250000,
		UplinkBytesPerSecond:   62500,
		UplinkDelay:            50 * time.Millisecond,
		UplinkLossBasisPoints:  100,
		UplinkImpairmentSeed:   99,
	}
	plan, err := NewPlan(cfg)
	if err != nil {
		t.Fatal(err)
	}
	want := "(ifIdx == 16 and (ip.DstAddr == 192.168.137.113 or ipv6.DstAddr == 2001:db8::8)) or (ifIdx == 12 and (ip.SrcAddr == 192.168.137.113 or ipv6.SrcAddr == 2001:db8::8))"
	if plan.Filter != want {
		t.Fatalf("filter = %q; want %q", plan.Filter, want)
	}

	cases := map[string]func(*Config){
		"missing_uplink_if":      func(c *Config) { c.UplinkInterfaceIndex = 0 },
		"colliding_uplink_if":    func(c *Config) { c.UplinkInterfaceIndex = c.InterfaceIndex },
		"uplink_rate_too_high":   func(c *Config) { c.UplinkBytesPerSecond = 1_000_000_001 },
		"negative_uplink_delay":  func(c *Config) { c.UplinkDelay = -time.Millisecond },
		"excessive_uplink_delay": func(c *Config) { c.UplinkDelay = 2001 * time.Millisecond },
		"excessive_uplink_loss":  func(c *Config) { c.UplinkLossBasisPoints = 5001 },
		"missing_uplink_seed":    func(c *Config) { c.UplinkImpairmentSeed = 0 },
		"uplink_delay_no_rate":   func(c *Config) { c.UplinkBytesPerSecond = 0; c.UplinkLossBasisPoints = 0; c.UplinkImpairmentSeed = 0 },
		"uplink_seed_no_rate":    func(c *Config) { c.UplinkBytesPerSecond = 0; c.UplinkDelay = 0; c.UplinkLossBasisPoints = 0 },
		"nat_no_uplink_rate": func(c *Config) {
			c.UplinkBytesPerSecond = 0
			c.UplinkDelay = 0
			c.UplinkLossBasisPoints = 0
			c.UplinkImpairmentSeed = 0
			c.UplinkNatAddresses = []string{"10.10.8.9"}
		},
		"nat_overlaps_phone": func(c *Config) { c.UplinkNatAddresses = []string{"192.168.137.113"} },
		"nat_invalid_cidr":   func(c *Config) { c.UplinkNatAddresses = []string{"10.10.8.0/24"} },
		"nat_loopback":       func(c *Config) { c.UplinkNatAddresses = []string{"127.0.0.1"} },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			bad := cfg
			mutate(&bad)
			if _, err := NewPlan(bad); err == nil {
				t.Fatalf("invalid uplink config %s was accepted", name)
			}
		})
	}

	natCfg := cfg
	natCfg.PhoneAddresses = []string{"192.168.137.113"}
	natCfg.UplinkNatAddresses = []string{"10.10.8.9"}
	natPlan, err := NewPlan(natCfg)
	if err != nil {
		t.Fatal(err)
	}
	wantNat := "(ifIdx == 16 and (ip.DstAddr == 192.168.137.113)) or (ifIdx == 12 and (ip.SrcAddr == 10.10.8.9))"
	if natPlan.Filter != wantNat {
		t.Fatalf("NAT filter = %q; want %q", natPlan.Filter, wantNat)
	}
}
