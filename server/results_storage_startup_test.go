package main

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestDisabledResultUploadStartupDoesNotCreateMissingStorage(t *testing.T) {
	dataDir := t.TempDir()

	authorizer, err := initializeResultUpload(dataDir, "")
	if err != nil {
		t.Fatalf("initialize disabled result upload: %v", err)
	}
	if authorizer != nil {
		t.Fatal("disabled result upload unexpectedly returned an authorizer")
	}
	if _, err := os.Lstat(filepath.Join(dataDir, "results")); !os.IsNotExist(err) {
		t.Fatalf("missing result storage was created or became unreadable: %v", err)
	}
}

func TestDisabledResultUploadStartupRejectsUnsafeStorage(t *testing.T) {
	tests := map[string]func(t *testing.T, dataDir string){
		"results is a file": func(t *testing.T, dataDir string) {
			if err := os.WriteFile(filepath.Join(dataDir, "results"), []byte("unsafe"), 0o600); err != nil {
				t.Fatal(err)
			}
		},
		"dated entry is a directory": func(t *testing.T, dataDir string) {
			resultsDir := filepath.Join(dataDir, "results")
			if err := os.Mkdir(resultsDir, 0o700); err != nil {
				t.Fatal(err)
			}
			if err := os.Mkdir(filepath.Join(resultsDir, "20260718.jsonl"), 0o700); err != nil {
				t.Fatal(err)
			}
		},
		"unexpected entry is present": func(t *testing.T, dataDir string) {
			resultsDir := filepath.Join(dataDir, "results")
			if err := os.Mkdir(resultsDir, 0o700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(filepath.Join(resultsDir, "notes.txt"), []byte("unsafe"), 0o600); err != nil {
				t.Fatal(err)
			}
		},
	}
	for name, prepare := range tests {
		t.Run(name, func(t *testing.T) {
			dataDir := t.TempDir()
			prepare(t, dataDir)

			authorizer, err := initializeResultUpload(dataDir, "")

			if err == nil {
				t.Fatal("unsafe result storage was accepted")
			}
			if authorizer != nil {
				t.Fatal("unsafe result storage returned an authorizer")
			}
		})
	}
}

func TestNonLinuxStartupRejectsExistingResultStorage(t *testing.T) {
	if runtime.GOOS == "linux" {
		t.Skip("Linux has the production permission-hardening implementation")
	}
	dataDir := t.TempDir()
	if err := os.Mkdir(filepath.Join(dataDir, "results"), 0o700); err != nil {
		t.Fatal(err)
	}

	authorizer, err := initializeResultUpload(dataDir, "")

	if err == nil || authorizer != nil {
		t.Fatal("non-Linux startup accepted existing result storage")
	}
}
