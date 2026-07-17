package gateway

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDedicatedGatewayAttestationMustMatchWANAndPrivateSubnet(t *testing.T) {
	path := filepath.Join(t.TempDir(), "attestation.json")
	raw := `{"contract_version":"aneb-dedicated-gateway-v1","dedicated_gateway":true,"wan_interface":"eth0","management_interface":"wlan0","exclusive_client_subnet":"192.168.77.0/24"}`
	if err := os.WriteFile(path, []byte(raw), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadAttestation(path, "eth0"); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadAttestation(path, "ens3"); err == nil {
		t.Fatal("mismatched WAN was accepted")
	}
}

func TestDedicatedGatewayAttestationRejectsWideHostBitAndTrailingContracts(t *testing.T) {
	for name, raw := range map[string]string{
		"wide":      `{"contract_version":"aneb-dedicated-gateway-v1","dedicated_gateway":true,"wan_interface":"eth0","management_interface":"eth1","exclusive_client_subnet":"192.168.0.0/16"}`,
		"host bits": `{"contract_version":"aneb-dedicated-gateway-v1","dedicated_gateway":true,"wan_interface":"eth0","management_interface":"eth1","exclusive_client_subnet":"192.168.77.1/24"}`,
		"trailing":  `{"contract_version":"aneb-dedicated-gateway-v1","dedicated_gateway":true,"wan_interface":"eth0","management_interface":"eth1","exclusive_client_subnet":"192.168.77.0/24"} {}`,
	} {
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "attestation.json")
			if err := os.WriteFile(path, []byte(raw), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := LoadAttestation(path, "eth0"); err == nil {
				t.Fatal("unsafe attestation was accepted")
			}
		})
	}
}
