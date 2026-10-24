package condition

import (
	"encoding/json"
	"path/filepath"
	"testing"
	"time"
)

func TestRequestProducesBoundedPlanWithoutOpeningMissingDriverDirectory(t *testing.T) {
	root := t.TempDir()
	want := Request{Format: "phone-forward-condition-1", Mode: Inspect, PhoneAddresses: []string{"192.0.2.8"}, InterfaceIndex: 12, TTLSeconds: 30, DownlinkBytesPerSecond: 125000, DriverDirectory: filepath.Join(root, "driver-not-created"), OutputDirectory: filepath.Join(root, "run-not-created")}
	data, _ := json.Marshal(want)
	got, plan, err := ParseRequest(data)
	if err != nil {
		t.Fatal(err)
	}
	if got.Mode != Inspect || got.DriverDirectory != want.DriverDirectory || plan.Config.TTL != 30*time.Second || plan.Filter != "ifIdx == 12 and (ip.DstAddr == 192.0.2.8)" {
		t.Fatalf("wrong finite plan: %+v %+v", got, plan)
	}
}
