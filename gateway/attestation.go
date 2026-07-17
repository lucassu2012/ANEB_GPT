package gateway

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strings"
)

const AttestationContractVersion = "aneb-dedicated-gateway-v1"

type Attestation struct {
	ContractVersion       string `json:"contract_version"`
	DedicatedGateway      bool   `json:"dedicated_gateway"`
	WANInterface          string `json:"wan_interface"`
	ManagementInterface   string `json:"management_interface"`
	ExclusiveClientSubnet string `json:"exclusive_client_subnet"`
}

func LoadAttestation(path, expectedWAN string) (Attestation, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return Attestation{}, fmt.Errorf("read dedicated gateway attestation: %w", err)
	}
	var attestation Attestation
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&attestation); err != nil {
		return Attestation{}, fmt.Errorf("decode dedicated gateway attestation: %w", err)
	}
	if attestation.ContractVersion != AttestationContractVersion || !attestation.DedicatedGateway {
		return Attestation{}, fmt.Errorf("host is not attested as a dedicated ANEB gateway")
	}
	if attestation.WANInterface != expectedWAN || attestation.ManagementInterface == expectedWAN {
		return Attestation{}, fmt.Errorf("attested interfaces do not match runtime configuration")
	}
	if !interfaceNamePattern.MatchString(attestation.ManagementInterface) || attestation.ManagementInterface == "lo" {
		return Attestation{}, fmt.Errorf("invalid management interface")
	}
	ip, network, err := net.ParseCIDR(attestation.ExclusiveClientSubnet)
	if err != nil || ip == nil || network == nil || !ip.IsPrivate() {
		return Attestation{}, fmt.Errorf("exclusive_client_subnet must be a private CIDR")
	}
	return attestation, nil
}
