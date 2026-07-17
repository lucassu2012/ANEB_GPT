package gateway

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

const tcStateContractVersion = "aneb-tc-ownership-v1"

type tcOwnershipState struct {
	ContractVersion string `json:"contract_version"`
	WAN             string `json:"wan_interface"`
	IFB             string `json:"ifb_interface"`
	IFBAlias        string `json:"ifb_alias"`
	BaselineQdisc   string `json:"baseline_qdisc"`
}

func loadTCState(path string) (*tcOwnershipState, error) {
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("lstat tc ownership state: %w", err)
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return nil, fmt.Errorf("tc ownership state must be a regular non-symlink file")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o022 != 0 {
		return nil, fmt.Errorf("tc ownership state must not be group/world writable")
	}
	if info.Size() <= 0 || info.Size() > 16<<10 {
		return nil, fmt.Errorf("tc ownership state size is invalid")
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open tc ownership state: %w", err)
	}
	defer f.Close()
	var state tcOwnershipState
	decoder := json.NewDecoder(io.LimitReader(f, 16<<10))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&state); err != nil {
		return nil, fmt.Errorf("decode tc ownership state: %w", err)
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return nil, fmt.Errorf("tc ownership state must contain one JSON object")
	}
	if state.ContractVersion != tcStateContractVersion || state.BaselineQdisc == "" {
		return nil, fmt.Errorf("tc ownership state contract is invalid")
	}
	if _, err := parseRestorableBaseline(state.BaselineQdisc); err != nil {
		return nil, fmt.Errorf("tc ownership baseline is not safely restorable: %w", err)
	}
	return &state, nil
}

func writeTCState(path string, state tcOwnershipState) error {
	if state.ContractVersion != tcStateContractVersion || state.BaselineQdisc == "" {
		return fmt.Errorf("refuse to write invalid tc ownership state")
	}
	if _, err := parseRestorableBaseline(state.BaselineQdisc); err != nil {
		return fmt.Errorf("refuse to write unrestorable tc ownership baseline: %w", err)
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return fmt.Errorf("create tc state directory: %w", err)
	}
	temp, err := os.CreateTemp(dir, ".tc-state-*")
	if err != nil {
		return fmt.Errorf("create tc state staging file: %w", err)
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err := temp.Chmod(0o600); err != nil {
		temp.Close()
		return fmt.Errorf("chmod tc state staging file: %w", err)
	}
	encoder := json.NewEncoder(temp)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(state); err != nil {
		temp.Close()
		return fmt.Errorf("encode tc ownership state: %w", err)
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return fmt.Errorf("sync tc ownership state: %w", err)
	}
	if err := temp.Close(); err != nil {
		return fmt.Errorf("close tc ownership state: %w", err)
	}
	if err := os.Rename(tempName, path); err != nil {
		return fmt.Errorf("publish tc ownership state: %w", err)
	}
	return syncDirectory(dir)
}

func removeTCState(path string) error {
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("remove tc ownership state: %w", err)
	}
	return syncDirectory(filepath.Dir(path))
}

func syncDirectory(path string) error {
	if runtime.GOOS == "windows" {
		return nil
	}
	dir, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open state directory for sync: %w", err)
	}
	defer dir.Close()
	if err := dir.Sync(); err != nil {
		return fmt.Errorf("sync state directory: %w", err)
	}
	return nil
}

func normalizeQdisc(value string) string {
	lines := strings.Split(strings.TrimSpace(value), "\n")
	for index := range lines {
		lines[index] = strings.Join(strings.Fields(lines[index]), " ")
	}
	return strings.Join(lines, "\n")
}
