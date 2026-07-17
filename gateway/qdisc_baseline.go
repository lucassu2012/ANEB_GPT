package gateway

import (
	"fmt"
	"regexp"
	"strings"
)

// qdiscBaseline is a deliberately small, machine-restorable subset of Linux
// root qdiscs.  We persist the normalized tc output, parse it again before
// cleanup, and only execute arguments that pass this whitelist.
type qdiscBaseline struct {
	Kind string
	Args []string
}

var (
	qdiscHandlePattern = regexp.MustCompile(`^[0-9A-Fa-f]+:$`)
	qdiscValuePattern  = regexp.MustCompile(`^[0-9]+(?:\.[0-9]+)?[A-Za-z]*$`)
	qdiscCountPattern  = regexp.MustCompile(`^[0-9]+$`)
)

var qdiscOptionOrder = map[string][]string{
	"fq_codel": {
		"limit", "flows", "quantum", "target", "interval", "memory_limit",
		"ce_threshold", "drop_batch", "ecn", "noecn",
	},
	"fq": {
		"limit", "flow_limit", "buckets", "orphan_mask", "quantum",
		"initial_quantum", "low_rate_threshold", "refill_delay", "timer_slack",
		"ce_threshold", "horizon", "pacing", "nopacing", "horizon_drop", "horizon_cap",
	},
}

var qdiscFlagOptions = map[string]bool{
	"ecn": true, "noecn": true,
	"pacing": true, "nopacing": true,
	"horizon_drop": true, "horizon_cap": true,
}

func parseRestorableBaseline(value string) (qdiscBaseline, error) {
	normalized := normalizeQdisc(value)
	if normalized == "" {
		return qdiscBaseline{}, fmt.Errorf("WAN root qdisc baseline is empty")
	}
	lines := strings.Split(normalized, "\n")
	if len(lines) != 1 {
		return qdiscBaseline{}, fmt.Errorf("WAN qdisc baseline is not a single supported root qdisc")
	}
	fields := strings.Fields(lines[0])
	if len(fields) < 4 || fields[0] != "qdisc" || !qdiscHandlePattern.MatchString(fields[2]) || fields[3] != "root" {
		return qdiscBaseline{}, fmt.Errorf("WAN root qdisc baseline is malformed")
	}
	kind := fields[1]
	index := 4
	if index < len(fields) && fields[index] == "refcnt" {
		index++
		if index >= len(fields) || !qdiscCountPattern.MatchString(fields[index]) {
			return qdiscBaseline{}, fmt.Errorf("WAN root qdisc refcnt is malformed")
		}
		index++
	}
	if kind == "noqueue" {
		if index != len(fields) {
			return qdiscBaseline{}, fmt.Errorf("noqueue baseline contains unsupported parameters")
		}
		return qdiscBaseline{Kind: kind}, nil
	}
	order, ok := qdiscOptionOrder[kind]
	if !ok {
		return qdiscBaseline{}, fmt.Errorf("WAN qdisc baseline kind %q is unsupported", kind)
	}
	allowed := make(map[string]bool, len(order))
	for _, option := range order {
		allowed[option] = true
	}
	values := make(map[string]string, len(order))
	for index < len(fields) {
		option := fields[index]
		index++
		if !allowed[option] {
			return qdiscBaseline{}, fmt.Errorf("%s baseline option %q is unsupported", kind, option)
		}
		if _, duplicate := values[option]; duplicate {
			return qdiscBaseline{}, fmt.Errorf("%s baseline option %q is duplicated", kind, option)
		}
		if qdiscFlagOptions[option] {
			values[option] = ""
			continue
		}
		if index >= len(fields) || !qdiscValuePattern.MatchString(fields[index]) {
			return qdiscBaseline{}, fmt.Errorf("%s baseline option %q has an unsafe value", kind, option)
		}
		restoreValue, valueErr := executableQdiscValue(kind, option, fields[index])
		if valueErr != nil {
			return qdiscBaseline{}, valueErr
		}
		values[option] = restoreValue
		index++
	}
	_, hasECN := values["ecn"]
	_, hasNoECN := values["noecn"]
	if hasECN && hasNoECN {
		return qdiscBaseline{}, fmt.Errorf("fq_codel baseline has conflicting ECN flags")
	}
	_, hasPacing := values["pacing"]
	_, hasNoPacing := values["nopacing"]
	if hasPacing && hasNoPacing {
		return qdiscBaseline{}, fmt.Errorf("fq baseline has conflicting pacing flags")
	}
	_, hasHorizonDrop := values["horizon_drop"]
	_, hasHorizonCap := values["horizon_cap"]
	if hasHorizonDrop && hasHorizonCap {
		return qdiscBaseline{}, fmt.Errorf("fq baseline has conflicting horizon flags")
	}
	args := make([]string, 0, len(fields)-4)
	for _, option := range order {
		value, present := values[option]
		if !present {
			continue
		}
		args = append(args, option)
		if !qdiscFlagOptions[option] {
			args = append(args, value)
		}
	}
	return qdiscBaseline{Kind: kind, Args: args}, nil
}

func executableQdiscValue(kind, option, value string) (string, error) {
	requireDecimal := func(candidate string) (string, error) {
		if !qdiscCountPattern.MatchString(candidate) {
			return "", fmt.Errorf("%s baseline option %q is not safely executable", kind, option)
		}
		return candidate, nil
	}
	stripPackets := func(candidate string) (string, error) {
		candidate = strings.TrimSuffix(candidate, "p")
		return requireDecimal(candidate)
	}
	stripBytes := func(candidate string) (string, error) {
		candidate = strings.TrimSuffix(candidate, "b")
		return requireDecimal(candidate)
	}
	if kind == "fq_codel" {
		switch option {
		case "limit":
			return stripPackets(value)
		case "flows", "quantum", "drop_batch":
			return requireDecimal(value)
		default:
			return value, nil
		}
	}
	switch option {
	case "limit", "flow_limit":
		return stripPackets(value)
	case "buckets", "orphan_mask":
		return requireDecimal(value)
	case "quantum", "initial_quantum":
		return stripBytes(value)
	default:
		return value, nil
	}
}

func (b qdiscBaseline) equal(other qdiscBaseline) bool {
	if b.Kind != other.Kind || len(b.Args) != len(other.Args) {
		return false
	}
	for index := range b.Args {
		if b.Args[index] != other.Args[index] {
			return false
		}
	}
	return true
}
