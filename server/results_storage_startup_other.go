//go:build !linux

package main

import (
	"fmt"
	"os"
	"path/filepath"
)

func hardenExistingResultsStoragePlatform(dataDir string) error {
	resultsDir := filepath.Join(dataDir, "results")
	_, err := os.Lstat(resultsDir)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("inspect existing result storage: %w", err)
	}
	return fmt.Errorf("existing result storage hardening is supported only on Linux")
}
