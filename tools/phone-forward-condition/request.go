package condition

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"path/filepath"
	"time"
)

type Request struct {
	Format                 string   `json:"format"`
	Mode                   Mode     `json:"mode"`
	PhoneAddresses         []string `json:"phone_addresses"`
	InterfaceIndex         uint32   `json:"interface_index"`
	TTLSeconds             int64    `json:"ttl_seconds"`
	DownlinkBytesPerSecond uint64   `json:"downlink_ip_bytes_per_second"`
	DriverDirectory        string   `json:"driver_directory"`
	OutputDirectory        string   `json:"output_directory"`
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
	plan, err := NewPlan(Config{PhoneAddresses: request.PhoneAddresses, InterfaceIndex: request.InterfaceIndex, TTL: time.Duration(request.TTLSeconds) * time.Second, DownlinkBytesPerSecond: request.DownlinkBytesPerSecond})
	return request, plan, err
}
