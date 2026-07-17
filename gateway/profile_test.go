package gateway

import (
	"path/filepath"
	"testing"
)

func TestPublishedProfilesValidateAndPreserveClaimBoundaries(t *testing.T) {
	profiles, err := LoadProfiles(filepath.Join("profiles"))
	if err != nil {
		t.Fatal(err)
	}
	if len(profiles) != 3 {
		t.Fatalf("profile count=%d", len(profiles))
	}
	outage := profiles["ip_outage_recovery@1.0.0"]
	if outage.Uplink.LossPct != 100 || outage.Downlink.LossPct != 100 || outage.DurationMs != 2_000 {
		t.Fatalf("unexpected outage contract: %+v", outage)
	}
	handover := profiles["ip_handover_gap@1.0.0"]
	if handover.ClaimScope != "dedicated_gateway_ip_forwarding_gap_not_radio_handover" {
		t.Fatalf("handover-like gap lost its boundary: %+v", handover)
	}
}

func TestProfileRejectsRadioClaimsAndUnboundedDuration(t *testing.T) {
	profile := validProfile()
	profile.ClaimScope = "radio_handover"
	if err := profile.Validate(); err == nil {
		t.Fatal("radio claim was accepted")
	}
	profile = validProfile()
	profile.DurationMs = 120_001
	if err := profile.Validate(); err == nil {
		t.Fatal("unbounded duration was accepted")
	}
}

func TestProfileFingerprintChangesWithAnyImpairmentParameter(t *testing.T) {
	profile := validProfile()
	fingerprint := profile.Fingerprint()
	if len(fingerprint) != 64 {
		t.Fatalf("fingerprint=%q", fingerprint)
	}
	changed := profile
	changed.Downlink.LossPct = 2
	if changed.Fingerprint() == fingerprint {
		t.Fatal("parameter drift did not change fingerprint")
	}
}

func validProfile() Profile {
	return Profile{
		ContractVersion:   ProfileContractVersion,
		ProfileID:         "test_profile",
		Version:           "1.0.0",
		Label:             "test",
		Kind:              "continuous",
		ClaimScope:        "dedicated_gateway_ip_forwarding",
		ImpairmentLayer:   "ip_forwarding",
		DurationMs:        100,
		ActivationDelayMs: 100,
		Uplink:            DirectionPolicy{DelayMs: 10, LossPct: 1},
		Downlink:          DirectionPolicy{DelayMs: 10, LossPct: 1},
		Excluded: []string{
			"radio_rsrp", "radio_rsrq", "radio_sinr", "base_station_scheduler", "actual_route_change",
		},
		EvidenceTier: "gateway_lab",
	}
}
