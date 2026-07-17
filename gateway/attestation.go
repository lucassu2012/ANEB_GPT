package gateway

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"runtime"
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
	info, err := os.Lstat(path)
	if err != nil {
		return Attestation{}, fmt.Errorf("stat dedicated gateway attestation: %w", err)
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() || info.Size() <= 0 || info.Size() > 16<<10 {
		return Attestation{}, fmt.Errorf("dedicated gateway attestation must be a bounded regular non-symlink file")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o022 != 0 {
		return Attestation{}, fmt.Errorf("dedicated gateway attestation must not be group/world writable")
	}
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
	var extra any
	if err := dec.Decode(&extra); !errors.Is(err, io.EOF) {
		return Attestation{}, fmt.Errorf("dedicated gateway attestation must contain one JSON object")
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
	ones, bits := 0, 0
	if network != nil {
		ones, bits = network.Mask.Size()
	}
	if err != nil || ip == nil || network == nil || ip.To4() == nil || !ip.IsPrivate() ||
		!ip.Equal(network.IP) || bits != 32 || ones < 24 || ones > 30 {
		return Attestation{}, fmt.Errorf("exclusive_client_subnet must be a canonical private IPv4 /24 through /30 CIDR")
	}
	return attestation, nil
}
