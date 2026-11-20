package condition

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/netip"
	"path/filepath"
	"time"
)

type Request struct {
	Format                       string   `json:"format"`
	Mode                         Mode     `json:"mode"`
	PhoneAddresses               []string `json:"phone_addresses"`
	InterfaceIndex               uint32   `json:"interface_index"`
	TTLSeconds                   int64    `json:"ttl_seconds"`
	DownlinkBytesPerSecond       uint64   `json:"downlink_ip_bytes_per_second"`
	DownlinkDelayMs              uint64   `json:"downlink_delay_ms,omitempty"`
	DownlinkLossBps              uint32   `json:"downlink_loss_bps,omitempty"`
	ImpairmentSeed               uint64   `json:"impairment_seed,omitempty"`
	DriverDirectory              string   `json:"driver_directory"`
	OutputDirectory              string   `json:"output_directory"`
	ExpectedUplinkInterfaceIndex uint32   `json:"expected_uplink_interface_index,omitempty"`
	ProbeRemoteAddresses         []string `json:"probe_remote_addresses,omitempty"`
}

func ParseRequest(data []byte) (Request, Plan, error) {
	var request Request
	if len(data) > 64<<10 {
		return request, Plan{}, errors.New("request exceeds64KiB")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&request); err != nil {
		return request, Plan{}, err
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return request, Plan{}, errors.New("request must be one JSON object")
	}
	if request.Format != "phone-forward-condition-1" || (request.Mode != Inspect && request.Mode != Limit) || request.TTLSeconds <= 0 || request.TTLSeconds > 120 {
		return request, Plan{}, errors.New("unsupported version/mode or lifetime")
	}
	if !filepath.IsAbs(request.DriverDirectory) || !filepath.IsAbs(request.OutputDirectory) || filepath.Clean(request.OutputDirectory) == filepath.VolumeName(request.OutputDirectory)+string(filepath.Separator) {
		return request, Plan{}, errors.New("absolute dependency and non-root output directory required")
	}
	if len(request.ProbeRemoteAddresses) > 16 {
		return request, Plan{}, errors.New("at most 16 probe_remote_addresses are allowed")
	}
	if len(request.ProbeRemoteAddresses) > 0 && request.ExpectedUplinkInterfaceIndex == 0 {
		return request, Plan{}, errors.New("probe_remote_addresses requires a nonzero expected_uplink_interface_index")
	}
	if request.ExpectedUplinkInterfaceIndex != 0 && request.ExpectedUplinkInterfaceIndex == request.InterfaceIndex {
		return request, Plan{}, errors.New("expected_uplink_interface_index must differ from forwarding interface_index")
	}
	if len(request.ProbeRemoteAddresses) > 0 {
		remotes := make([]string, 0, len(request.ProbeRemoteAddresses))
		for _, raw := range request.ProbeRemoteAddresses {
			address, err := netip.ParseAddr(raw)
			if err != nil || address.Zone() != "" || !address.IsGlobalUnicast() {
				return request, Plan{}, fmt.Errorf("expected a unicast probe_remote_addresses entry, not a range or filter: %q", raw)
			}
			remotes = append(remotes, address.Unmap().String())
		}
		request.ProbeRemoteAddresses = remotes
	}
	plan, err := NewPlan(Config{
		PhoneAddresses:          request.PhoneAddresses,
		InterfaceIndex:          request.InterfaceIndex,
		TTL:                     time.Duration(request.TTLSeconds) * time.Second,
		DownlinkBytesPerSecond:  request.DownlinkBytesPerSecond,
		DownlinkDelay:           time.Duration(request.DownlinkDelayMs) * time.Millisecond,
		DownlinkLossBasisPoints: request.DownlinkLossBps,
		ImpairmentSeed:          request.ImpairmentSeed,
	})
	return request, plan, err
}
