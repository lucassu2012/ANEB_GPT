package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
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
