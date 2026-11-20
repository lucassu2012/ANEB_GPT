// Package condition is a bounded phone-forwarding experiment helper, not a KPI engine.
package condition

import (
	"fmt"
	"net/netip"
	"strings"
	"time"
)

type QueueOverflowPolicy string

const (
	QueueInvalidate QueueOverflowPolicy = "invalidate"
	QueueTailDrop   QueueOverflowPolicy = "tail_drop"
)

type Config struct {
	PhoneAddresses          []string
	InterfaceIndex          uint32
	TTL                     time.Duration
	DownlinkBytesPerSecond  uint64
	DownlinkDelay           time.Duration
	DownlinkLossBasisPoints uint32
	ImpairmentSeed          uint64
	QueueOverflowPolicy     QueueOverflowPolicy
	QueueLimitBytes         uint32
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
	if config.DownlinkDelay < 0 || config.DownlinkDelay > 2*time.Second {
		return Plan{}, fmt.Errorf("downlink delay must be within 0..2000ms")
	}
	if config.DownlinkLossBasisPoints > 5000 {
		return Plan{}, fmt.Errorf("downlink loss must be within 0..5000 bps (0..50%%)")
	}
	if config.DownlinkLossBasisPoints > 0 && config.ImpairmentSeed == 0 {
		return Plan{}, fmt.Errorf("downlink loss requires a nonzero impairment_seed for deterministic replay")
	}
	switch config.QueueOverflowPolicy {
	case "", QueueInvalidate:
		config.QueueOverflowPolicy = QueueInvalidate
	case QueueTailDrop:
	default:
		return Plan{}, fmt.Errorf("unsupported queue_overflow_policy: %q", config.QueueOverflowPolicy)
	}
	if config.QueueLimitBytes != 0 && (config.QueueLimitBytes < 16<<10 || config.QueueLimitBytes > maxPendingBufferBytes) {
		return Plan{}, fmt.Errorf("queue_limit_bytes must be 0 or within %d..%d", 16<<10, maxPendingBufferBytes)
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
