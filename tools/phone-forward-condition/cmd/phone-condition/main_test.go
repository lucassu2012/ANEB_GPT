package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	condition "aneb-phone-forward-condition"
)

func TestPlanCommandDoesNotNeedExistingDriverOrOutput(t *testing.T) {
	root := t.TempDir()
	request := condition.Request{Format: "phone-forward-condition-1", Mode: condition.Inspect, PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTLSeconds: 30, DownlinkBytesPerSecond: 125000, DriverDirectory: filepath.Join(root, "unavailable-driver"), OutputDirectory: filepath.Join(root, "must-not-create")}
	data, _ := json.Marshal(request)
	path := filepath.Join(root, "request.json")
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	if err := run([]string{"plan", "-config", path}, &output); err != nil {
		t.Fatal(err)
	}
	var plan struct {
		State         string `json:"state"`
		Filter        string `json:"filter"`
		DriverOpened  bool   `json:"driver_opened"`
		RequestSHA256 string `json:"request_sha256"`
	}
	if err := json.Unmarshal(output.Bytes(), &plan); err != nil {
		t.Fatal(err)
	}
	if plan.State != "NOT_RUN" || plan.DriverOpened || plan.Filter != "ifIdx == 12 and (ip.DstAddr == 192.0.2.8)" || len(plan.RequestSHA256) != 64 {
		t.Fatalf("misleading plan: %+v", plan)
	}
	if _, err := os.Stat(request.OutputDirectory); !os.IsNotExist(err) {
		t.Fatal("planning created runtime output")
	}
}

func TestPlanCommandEchoesOptionalUplinkFieldsWhenConfigured(t *testing.T) {
	root := t.TempDir()
	request := condition.Request{
		Format:                       "phone-forward-condition-1",
		Mode:                         condition.Limit,
		PhoneAddresses:               []string{"192.168.137.42"},
		InterfaceIndex:               14,
		TTLSeconds:                   35,
		DownlinkBytesPerSecond:       125000,
		DriverDirectory:              filepath.Join(root, "unavailable-driver"),
		OutputDirectory:              filepath.Join(root, "must-not-create"),
		ExpectedUplinkInterfaceIndex: 10,
		ProbeRemoteAddresses:         []string{"198.51.100.20"},
	}
	data, _ := json.Marshal(request)
	path := filepath.Join(root, "request.json")
	if err := os.WriteFile(path, data, 0600); err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	if err := run([]string{"plan", "-config", path}, &output); err != nil {
		t.Fatal(err)
	}
	var plan struct {
		ExpectedUplinkInterfaceIndex uint32   `json:"expected_uplink_interface_index"`
		ProbeRemoteAddresses         []string `json:"probe_remote_addresses"`
	}
	if err := json.Unmarshal(output.Bytes(), &plan); err != nil {
		t.Fatal(err)
	}
	if plan.ExpectedUplinkInterfaceIndex != 10 || len(plan.ProbeRemoteAddresses) != 1 || plan.ProbeRemoteAddresses[0] != "198.51.100.20" {
		t.Fatalf("plan lost uplink config: %+v", plan)
	}
}

func TestPlanCommandEchoesQueuePolicyAndLimitWhenConfiguredAndOmitsDefault(t *testing.T) {
	root := t.TempDir()
	base := condition.Request{
		Format:                 "phone-forward-condition-1",
		Mode:                   condition.Limit,
		PhoneAddresses:         []string{"192.168.137.42"},
		InterfaceIndex:         14,
		TTLSeconds:             35,
		DownlinkBytesPerSecond: 125000,
		DriverDirectory:        filepath.Join(root, "unavailable-driver"),
		OutputDirectory:        filepath.Join(root, "must-not-create"),
	}
	defData, _ := json.Marshal(base)
	defPath := filepath.Join(root, "default-request.json")
	if err := os.WriteFile(defPath, defData, 0600); err != nil {
		t.Fatal(err)
	}
	var defOutput bytes.Buffer
	if err := run([]string{"plan", "-config", defPath}, &defOutput); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(defOutput.String(), "queue_overflow_policy") || strings.Contains(defOutput.String(), "queue_limit_bytes") {
		t.Fatalf("unset queue fields leaked into default plan output: %s", defOutput.String())
	}

	configured := base
	configured.QueueOverflowPolicy = condition.QueueTailDrop
	configured.QueueLimitBytes = 64 << 10
	cfgData, _ := json.Marshal(configured)
	cfgPath := filepath.Join(root, "taildrop-request.json")
	if err := os.WriteFile(cfgPath, cfgData, 0600); err != nil {
		t.Fatal(err)
	}
	var cfgOutput bytes.Buffer
	if err := run([]string{"plan", "-config", cfgPath}, &cfgOutput); err != nil {
		t.Fatal(err)
	}
	var plan struct {
		QueueOverflowPolicy condition.QueueOverflowPolicy `json:"queue_overflow_policy"`
		QueueLimitBytes     uint32                        `json:"queue_limit_bytes"`
	}
	if err := json.Unmarshal(cfgOutput.Bytes(), &plan); err != nil {
		t.Fatal(err)
	}
	if plan.QueueOverflowPolicy != condition.QueueTailDrop || plan.QueueLimitBytes != 64<<10 {
		t.Fatalf("plan lost queue config: %+v", plan)
	}
}
