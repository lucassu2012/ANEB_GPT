// Package condition is a bounded phone-forwarding experiment helper, not a KPI engine.
package condition

import (
	"fmt"
	"net/netip"
	"strings"
	"time"
)

type Config struct {
	PhoneAddresses         []string
	InterfaceIndex         uint32
	TTL                    time.Duration
	DownlinkBytesPerSecond uint64
}

type Plan struct {
	Config       Config
	Filter       string
	destinations map[netip.Addr]struct{}
}

func NewPlan(config Config) (Plan, error) {
	if config.TTL <= 0 || config.TTL > 120*time.Second || config.InterfaceIndex == 0 || len(config.PhoneAddresses) == 0 || len(config.PhoneAddresses) > 16 || config.DownlinkBytesPerSecond == 0 || config.DownlinkBytesPerSecond > 1_000_000_000 {
		return Plan{}, fmt.Errorf("condition requires one address set, a nonzero interface, 0<TTL<=120s and 0<IP-bytes/s<=1000000000")
	}
	terms := make([]string, 0, len(config.PhoneAddresses))
	addresses := make([]string, 0, len(config.PhoneAddresses))
	destinations := make(map[netip.Addr]struct{}, len(config.PhoneAddresses))
	for _, raw := range config.PhoneAddresses {
		address, err := netip.ParseAddr(raw)
		if err != nil || address.Zone() != "" || !address.IsGlobalUnicast() {
			return Plan{}, fmt.Errorf("expected a unicast host address, not a range or filter: %q", raw)
		}
		address = address.Unmap()
		field := "ip.DstAddr"
		if address.Is6() {
			field = "ipv6.DstAddr"
		}
		addresses = append(addresses, address.String())
		destinations[address] = struct{}{}
		terms = append(terms, field+" == "+address.String())
	}
	config.PhoneAddresses = addresses
	return Plan{Config: config, Filter: fmt.Sprintf("ifIdx == %d and (%s)", config.InterfaceIndex, strings.Join(terms, " or ")), destinations: destinations}, nil
}
