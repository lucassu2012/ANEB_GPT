package gateway

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

const ProfileContractVersion = "aneb-gateway-profile-v1"

var identifierPattern = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,63}$`)

// DirectionPolicy is applied to forwarded traffic at the dedicated gateway.
// Uplink is WAN egress; downlink is WAN ingress redirected to an IFB device.
type DirectionPolicy struct {
	RateMbps float64 `json:"rate_mbps,omitempty"`
	DelayMs  int     `json:"delay_ms,omitempty"`
	JitterMs int     `json:"jitter_ms,omitempty"`
	LossPct  float64 `json:"loss_pct,omitempty"`
}

type Profile struct {
	ContractVersion   string          `json:"contract_version"`
	ProfileID         string          `json:"profile_id"`
	Version           string          `json:"version"`
	Label             string          `json:"label"`
	Kind              string          `json:"kind"`
	ClaimScope        string          `json:"claim_scope"`
	ImpairmentLayer   string          `json:"impairment_layer"`
	DurationMs        int             `json:"duration_ms"`
	ActivationDelayMs int             `json:"activation_delay_ms"`
	Uplink            DirectionPolicy `json:"uplink"`
	Downlink          DirectionPolicy `json:"downlink"`
	Excluded          []string        `json:"excluded_from_impairment"`
	EvidenceTier      string          `json:"evidence_tier"`
}

func (p Profile) Ref() string { return p.ProfileID + "@" + p.Version }

// Fingerprint binds the exact allowlisted gateway behavior to App evidence.
// Struct JSON field order is deterministic; excluded values are sorted first.
func (p Profile) Fingerprint() string {
	canonical := p
	canonical.Excluded = append([]string(nil), p.Excluded...)
	sort.Strings(canonical.Excluded)
	raw, _ := json.Marshal(canonical)
	return fmt.Sprintf("%x", sha256.Sum256(raw))
}

func (p Profile) Validate() error {
	if p.ContractVersion != ProfileContractVersion {
		return fmt.Errorf("unsupported contract_version %q", p.ContractVersion)
	}
	if !identifierPattern.MatchString(p.ProfileID) {
		return fmt.Errorf("invalid profile_id %q", p.ProfileID)
	}
	if !identifierPattern.MatchString(p.Version) {
		return fmt.Errorf("invalid version %q", p.Version)
	}
	if strings.TrimSpace(p.Label) == "" {
		return fmt.Errorf("label is required")
	}
	if p.Kind != "continuous" && p.Kind != "outage" && p.Kind != "handover_gap" {
		return fmt.Errorf("unsupported kind %q", p.Kind)
	}
	if p.ImpairmentLayer != "ip_forwarding" {
		return fmt.Errorf("impairment_layer must be ip_forwarding")
	}
	if p.ClaimScope != "dedicated_gateway_ip_forwarding" &&
		p.ClaimScope != "dedicated_gateway_ip_forwarding_gap_not_radio_handover" {
		return fmt.Errorf("unsupported claim_scope %q", p.ClaimScope)
	}
	if p.DurationMs < 100 || p.DurationMs > 120_000 {
		return fmt.Errorf("duration_ms must be in [100,120000]")
	}
	if p.ActivationDelayMs < 100 || p.ActivationDelayMs > 5_000 {
		return fmt.Errorf("activation_delay_ms must be in [100,5000]")
	}
	if err := validateDirection("uplink", p.Uplink); err != nil {
		return err
	}
	if err := validateDirection("downlink", p.Downlink); err != nil {
		return err
	}
	if p.Kind == "outage" && (p.Uplink.LossPct != 100 || p.Downlink.LossPct != 100) {
		return fmt.Errorf("outage profiles must declare 100%% loss in both directions")
	}
	if p.EvidenceTier != "gateway_lab" {
		return fmt.Errorf("evidence_tier must be gateway_lab")
	}
	requiredExcluded := map[string]bool{
		"radio_rsrp": false, "radio_rsrq": false, "radio_sinr": false,
		"base_station_scheduler": false, "actual_route_change": false,
	}
	for _, value := range p.Excluded {
		if _, ok := requiredExcluded[value]; ok {
			requiredExcluded[value] = true
		}
	}
	for value, found := range requiredExcluded {
		if !found {
			return fmt.Errorf("excluded_from_impairment missing %q", value)
		}
	}
	return nil
}

func validateDirection(name string, p DirectionPolicy) error {
	if p.RateMbps < 0 || p.RateMbps > 10_000 {
		return fmt.Errorf("%s rate_mbps out of range", name)
	}
	if p.DelayMs < 0 || p.DelayMs > 10_000 {
		return fmt.Errorf("%s delay_ms out of range", name)
	}
	if p.JitterMs < 0 || p.JitterMs > 5_000 {
		return fmt.Errorf("%s jitter_ms out of range", name)
	}
	if p.DelayMs == 0 && p.JitterMs != 0 {
		return fmt.Errorf("%s jitter requires delay", name)
	}
	if p.LossPct < 0 || p.LossPct > 100 {
		return fmt.Errorf("%s loss_pct out of range", name)
	}
	if p.RateMbps == 0 && p.DelayMs == 0 && p.LossPct == 0 {
		return fmt.Errorf("%s has no impairment", name)
	}
	return nil
}

func LoadProfiles(dir string) (map[string]Profile, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, fmt.Errorf("read profile directory: %w", err)
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	profiles := make(map[string]Profile)
	for _, entry := range entries {
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".json" {
			continue
		}
		raw, err := os.ReadFile(filepath.Join(dir, entry.Name()))
		if err != nil {
			return nil, fmt.Errorf("read %s: %w", entry.Name(), err)
		}
		var p Profile
		dec := json.NewDecoder(strings.NewReader(string(raw)))
		dec.DisallowUnknownFields()
		if err := dec.Decode(&p); err != nil {
			return nil, fmt.Errorf("decode %s: %w", entry.Name(), err)
		}
		if err := p.Validate(); err != nil {
			return nil, fmt.Errorf("validate %s: %w", entry.Name(), err)
		}
		if _, exists := profiles[p.Ref()]; exists {
			return nil, fmt.Errorf("duplicate profile %s", p.Ref())
		}
		profiles[p.Ref()] = p
	}
	if len(profiles) == 0 {
		return nil, fmt.Errorf("no gateway profiles found in %s", dir)
	}
	return profiles, nil
}
