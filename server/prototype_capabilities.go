package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"os"
)

const prototypeProfileManifestSHA256 = "44393ddd5ed11a5091038a85d08ab65ee91a8566997e837d2c40fd3add57d5dc"

type prototypeCapabilities struct {
	SchemaVersion          string               `json:"schema_version"`
	ProductVersion         string               `json:"product_version"`
	ProtocolVersion        string               `json:"protocol_version"`
	ServerVersion          string               `json:"server_version"`
	ServerBinarySHA256     string               `json:"server_binary_sha256"`
	ClaimScope             string               `json:"claim_scope"`
	EvidenceMode           string               `json:"evidence_mode"`
	ImpairmentLayer        string               `json:"impairment_layer"`
	ProfileManifestSHA256  string               `json:"profile_manifest_sha256"`
	Workload               prototypeWorkload    `json:"workload"`
	Conditions             []prototypeCondition `json:"conditions"`
	EvidenceSchemaVersion  string               `json:"evidence_schema_version"`
	ScorePolicyID          string               `json:"score_policy_id"`
	TerminalReceiptVersion string               `json:"terminal_receipt_version"`
}

type prototypeWorkload struct {
	ID                string `json:"id"`
	Version           string `json:"version"`
	ContentEventCount int    `json:"content_event_count"`
}

type prototypeCondition struct {
	ID                string `json:"id"`
	Version           string `json:"version"`
	NominalIntervalMs int64  `json:"nominal_interval_ms"`
	ScheduleSHA256    string `json:"schedule_sha256"`
}

func prototypeCapabilitiesDocument() (prototypeCapabilities, error) {
	binarySHA, err := prototypeServerBinarySHA256()
	if err != nil {
		return prototypeCapabilities{}, err
	}
	conditions := make([]prototypeCondition, 0, 3)
	for _, id := range []string{"baseline_v0.1", "slow_v0.1", "unstable_v0.1"} {
		schedule, err := GeneratePrototypeSchedule(id)
		if err != nil {
			return prototypeCapabilities{}, err
		}
		conditions = append(conditions, prototypeCondition{
			ID:                schedule.ConditionID,
			Version:           schedule.Version,
			NominalIntervalMs: schedule.NominalIntervalMs,
			ScheduleSHA256:    schedule.ScheduleHash,
		})
	}
	return prototypeCapabilities{
		SchemaVersion:          "aneb-prototype-capabilities-0.1",
		ProductVersion:         "prototype-0.1",
		ProtocolVersion:        "prototype-stream-0.1",
		ServerVersion:          serverVersion,
		ServerBinarySHA256:     binarySHA,
		ClaimScope:             "application_end_to_end_to_probe_node",
		EvidenceMode:           "synthetic_application_impairment",
		ImpairmentLayer:        "application",
		ProfileManifestSHA256:  prototypeProfileManifestSHA256,
		Workload:               prototypeWorkload{ID: "streaming_text_reference_v0.1", Version: "0.1", ContentEventCount: 120},
		Conditions:             conditions,
		EvidenceSchemaVersion:  "aneb-prototype-evidence-0.1",
		ScorePolicyID:          "rpi-0.1",
		TerminalReceiptVersion: prototypeCanonicalTerminalReceiptVersion,
	}, nil
}

func prototypeServerBinarySHA256() (string, error) {
	path, err := os.Executable()
	if err != nil {
		return "", err
	}
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func (a *app) handlePrototypeCapabilities(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	document, err := prototypeCapabilitiesDocument()
	if err != nil {
		http.Error(w, "capabilities unavailable", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(document); err != nil {
		return
	}
}
