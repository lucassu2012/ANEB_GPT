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
	UplinkInterfaceIndex    uint32
	TTL                     time.Duration
	DownlinkBytesPerSecond  uint64
	DownlinkDelay           time.Duration
	DownlinkLossBasisPoints uint32
	ImpairmentSeed          uint64
	UplinkBytesPerSecond    uint64
	UplinkDelay             time.Duration
	UplinkLossBasisPoints   uint32
	UplinkImpairmentSeed    uint64
	UplinkNatAddresses      []string
	QueueOverflowPolicy     QueueOverflowPolicy
	QueueLimitBytes         uint32
}

func (c Config) HasUplink() bool {
	return c.UplinkBytesPerSecond > 0
}

type Plan struct {
	Config        Config
	Filter        string
	phones        map[netip.Addr]struct{}
	uplinkSources map[netip.Addr]struct{}
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
	if config.UplinkBytesPerSecond == 0 {
		if config.UplinkDelay != 0 || config.UplinkLossBasisPoints != 0 || config.UplinkImpairmentSeed != 0 || len(config.UplinkNatAddresses) > 0 {
			return Plan{}, fmt.Errorf("uplink delay/loss/seed/nat_addresses require nonzero uplink_ip_bytes_per_second")
		}
	} else {
		if config.UplinkBytesPerSecond > 1_000_000_000 {
			return Plan{}, fmt.Errorf("uplink IP-bytes/s must be within 1..1000000000")
		}
		if config.UplinkInterfaceIndex == 0 || config.UplinkInterfaceIndex == config.InterfaceIndex {
			return Plan{}, fmt.Errorf("bidirectional shaping requires a nonzero expected_uplink_interface_index distinct from interface_index")
		}
		if config.UplinkDelay < 0 || config.UplinkDelay > 2*time.Second {
			return Plan{}, fmt.Errorf("uplink delay must be within 0..2000ms")
		}
		if config.UplinkLossBasisPoints > 5000 {
			return Plan{}, fmt.Errorf("uplink loss must be within 0..5000 bps (0..50%%)")
		}
		if config.UplinkLossBasisPoints > 0 && config.UplinkImpairmentSeed == 0 {
			return Plan{}, fmt.Errorf("uplink loss requires a nonzero uplink_impairment_seed for deterministic replay")
		}
		if len(config.UplinkNatAddresses) > 16 {
			return Plan{}, fmt.Errorf("at most 16 uplink_nat_addresses are allowed")
		}
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
	dstTerms := make([]string, 0, len(config.PhoneAddresses))
	srcTerms := make([]string, 0, len(config.PhoneAddresses))
	addresses := make([]string, 0, len(config.PhoneAddresses))
	phones := make(map[netip.Addr]struct{}, len(config.PhoneAddresses))
	for _, raw := range config.PhoneAddresses {
		address, err := netip.ParseAddr(raw)
		if err != nil || address.Zone() != "" || !address.IsGlobalUnicast() {
			return Plan{}, fmt.Errorf("expected a unicast host address, not a range or filter: %q", raw)
		}
		address = address.Unmap()
		dstField, srcField := "ip.DstAddr", "ip.SrcAddr"
		if address.Is6() {
			dstField, srcField = "ipv6.DstAddr", "ipv6.SrcAddr"
		}
		addrStr := address.String()
		addresses = append(addresses, addrStr)
		phones[address] = struct{}{}
		dstTerms = append(dstTerms, dstField+" == "+addrStr)
		srcTerms = append(srcTerms, srcField+" == "+addrStr)
	}
	config.PhoneAddresses = addresses
	var uplinkSources map[netip.Addr]struct{}
	uplinkSrcTerms := srcTerms
	if config.HasUplink() {
		if len(config.UplinkNatAddresses) > 0 {
			uplinkSources = make(map[netip.Addr]struct{}, len(config.UplinkNatAddresses))
			natAddresses := make([]string, 0, len(config.UplinkNatAddresses))
			natTerms := make([]string, 0, len(config.UplinkNatAddresses))
			for _, raw := range config.UplinkNatAddresses {
				address, err := netip.ParseAddr(raw)
				if err != nil || address.Zone() != "" || !address.IsGlobalUnicast() {
					return Plan{}, fmt.Errorf("expected a unicast uplink NAT address, not a range or filter: %q", raw)
				}
				address = address.Unmap()
				if _, overlap := phones[address]; overlap {
					return Plan{}, fmt.Errorf("uplink_nat_addresses must differ from phone_addresses: %q", raw)
				}
				srcField := "ip.SrcAddr"
				if address.Is6() {
					srcField = "ipv6.SrcAddr"
				}
				addrStr := address.String()
				natAddresses = append(natAddresses, addrStr)
				uplinkSources[address] = struct{}{}
				natTerms = append(natTerms, srcField+" == "+addrStr)
			}
			config.UplinkNatAddresses = natAddresses
			uplinkSrcTerms = natTerms
		} else {
			uplinkSources = phones
		}
	}
	downlinkExpr := fmt.Sprintf("ifIdx == %d and (%s)", config.InterfaceIndex, strings.Join(dstTerms, " or "))
	filter := downlinkExpr
	if config.HasUplink() {
		uplinkExpr := fmt.Sprintf("ifIdx == %d and (%s)", config.UplinkInterfaceIndex, strings.Join(uplinkSrcTerms, " or "))
		filter = fmt.Sprintf("(%s) or (%s)", downlinkExpr, uplinkExpr)
	}
	return Plan{Config: config, Filter: filter, phones: phones, uplinkSources: uplinkSources}, nil
}
