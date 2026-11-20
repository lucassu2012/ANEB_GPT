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
