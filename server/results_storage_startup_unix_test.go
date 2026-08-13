//go:build linux

package main

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

func TestDisabledResultUploadStartupHardensExistingLegacyStorage(t *testing.T) {
	dataDir := t.TempDir()
	resultsDir := filepath.Join(dataDir, "results")
	if err := os.Mkdir(resultsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	files := map[string][]byte{
		"20260718.jsonl": []byte("legacy-one\n"),
		"20260719.jsonl": []byte("legacy-two\n"),
	}
	for name, content := range files {
		if err := os.WriteFile(filepath.Join(resultsDir, name), content, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Chmod(resultsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	assertPermissionMode(t, resultsDir, 0o755)
	for name := range files {
		path := filepath.Join(resultsDir, name)
		if err := os.Chmod(path, 0o644); err != nil {
			t.Fatal(err)
		}
		assertPermissionMode(t, path, 0o644)
	}

	authorizer, err := initializeResultUpload(dataDir, "")
	if err != nil {
		t.Fatalf("initialize disabled result upload: %v", err)
	}
	if authorizer != nil {
		t.Fatal("disabled result upload unexpectedly returned an authorizer")
	}
	assertPermissionMode(t, resultsDir, 0o700)
	for name, expected := range files {
		path := filepath.Join(resultsDir, name)
		assertPermissionMode(t, path, 0o600)
		actual, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if string(actual) != string(expected) {
			t.Fatalf("legacy result bytes changed for %s", name)
		}
	}
}

func TestDisabledResultUploadStartupNeverFollowsStorageSymlinks(t *testing.T) {
	t.Run("results directory", func(t *testing.T) {
		dataDir := t.TempDir()
		targetDir := t.TempDir()
		targetFile := filepath.Join(targetDir, "20260718.jsonl")
		if err := os.WriteFile(targetFile, []byte("outside\n"), 0o644); err != nil {
			t.Fatal(err)
		}
		targetDirMode := permissionMode(t, targetDir)
		targetFileMode := permissionMode(t, targetFile)
		if err := os.Symlink(targetDir, filepath.Join(dataDir, "results")); err != nil {
			t.Fatal(err)
		}

		authorizer, err := initializeResultUpload(dataDir, "")

		if err == nil || authorizer != nil {
			t.Fatal("result storage directory symlink was accepted")
		}
		assertPermissionMode(t, targetDir, targetDirMode)
		assertPermissionMode(t, targetFile, targetFileMode)
	})

	t.Run("result file", func(t *testing.T) {
		dataDir := t.TempDir()
		resultsDir := filepath.Join(dataDir, "results")
		if err := os.Mkdir(resultsDir, 0o755); err != nil {
			t.Fatal(err)
		}
		target := filepath.Join(t.TempDir(), "outside.jsonl")
		if err := os.WriteFile(target, []byte("outside\n"), 0o644); err != nil {
			t.Fatal(err)
		}
		targetMode := permissionMode(t, target)
		if err := os.Symlink(target, filepath.Join(resultsDir, "20260718.jsonl")); err != nil {
			t.Fatal(err)
		}

		authorizer, err := initializeResultUpload(dataDir, "")

		if err == nil || authorizer != nil {
			t.Fatal("result file symlink was accepted")
		}
		assertPermissionMode(t, target, targetMode)
		actual, readErr := os.ReadFile(target)
		if readErr != nil {
			t.Fatal(readErr)
		}
		if string(actual) != "outside\n" {
			t.Fatal("result file symlink target bytes changed")
		}
	})
}

func TestDisabledResultUploadStartupRejectsHardLinkedArchivesWithoutChangingTarget(t *testing.T) {
	dataDir := t.TempDir()
	resultsDir := filepath.Join(dataDir, "results")
	if err := os.Mkdir(resultsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(t.TempDir(), "outside.jsonl")
	if err := os.WriteFile(target, []byte("outside\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	targetMode := permissionMode(t, target)
	if err := os.Link(target, filepath.Join(resultsDir, "20260718.jsonl")); err != nil {
		t.Fatal(err)
	}

	authorizer, err := initializeResultUpload(dataDir, "")

	if err == nil || authorizer != nil {
		t.Fatal("hard-linked result archive was accepted")
	}
	assertPermissionMode(t, target, targetMode)
	actual, readErr := os.ReadFile(target)
	if readErr != nil {
		t.Fatal(readErr)
	}
	if string(actual) != "outside\n" {
		t.Fatal("hard-linked result target bytes changed")
	}
}

func TestResultUploadStartupValidatesCredentialBeforeChangingLegacyPermissions(t *testing.T) {
	dataDir := t.TempDir()
	resultsDir := filepath.Join(dataDir, "results")
	archive := filepath.Join(resultsDir, "20260718.jsonl")
	if err := os.Mkdir(resultsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(archive, []byte("legacy\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	tokenFile := filepath.Join(t.TempDir(), "token")
	if err := os.WriteFile(tokenFile, []byte("not-a-valid-token\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	authorizer, err := initializeResultUpload(dataDir, tokenFile)

	if err == nil || authorizer != nil {
		t.Fatal("invalid result upload credential was accepted")
	}
	assertPermissionMode(t, resultsDir, 0o755)
	assertPermissionMode(t, archive, 0o644)
}

func TestDisabledResultUploadStartupRejectsSymlinkedDataDirectory(t *testing.T) {
	root := t.TempDir()
	targetData := t.TempDir()
	resultsDir := filepath.Join(targetData, "results")
	archive := filepath.Join(resultsDir, "20260718.jsonl")
	if err := os.Mkdir(resultsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(archive, []byte("outside\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(resultsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(archive, 0o644); err != nil {
		t.Fatal(err)
	}
	dataDir := filepath.Join(root, "data")
	if err := os.Symlink(targetData, dataDir); err != nil {
		t.Fatal(err)
	}

	authorizer, err := initializeResultUpload(dataDir, "")

	if err == nil || authorizer != nil {
		t.Fatal("symlinked data directory was accepted")
	}
	assertPermissionMode(t, resultsDir, 0o755)
	assertPermissionMode(t, archive, 0o644)
}

func TestDisabledResultUploadStartupPreflightsEveryEntryBeforeChangingModes(t *testing.T) {
	dataDir := t.TempDir()
	resultsDir := filepath.Join(dataDir, "results")
	archive := filepath.Join(resultsDir, "20000101.jsonl")
	if err := os.Mkdir(resultsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(archive, []byte("valid\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(resultsDir, "zz-invalid.txt"), []byte("invalid\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(resultsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(archive, 0o644); err != nil {
		t.Fatal(err)
	}

	authorizer, err := initializeResultUpload(dataDir, "")

	if err == nil || authorizer != nil {
		t.Fatal("mixed valid and unsafe result storage was accepted")
	}
	assertPermissionMode(t, resultsDir, 0o755)
	assertPermissionMode(t, archive, 0o644)
	actual, readErr := os.ReadFile(archive)
	if readErr != nil || string(actual) != "valid\n" {
		t.Fatalf("valid archive changed: bytes=%q err=%v", actual, readErr)
	}
}

func TestDisabledResultUploadStartupRejectsDatedFIFOWithoutBlocking(t *testing.T) {
	dataDir := t.TempDir()
	resultsDir := filepath.Join(dataDir, "results")
	if err := os.Mkdir(resultsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := unix.Mkfifo(filepath.Join(resultsDir, "20260718.jsonl"), 0o600); err != nil {
		t.Fatal(err)
	}
	started := time.Now()

	authorizer, err := initializeResultUpload(dataDir, "")

	if err == nil || authorizer != nil {
		t.Fatal("dated FIFO was accepted")
	}
	if elapsed := time.Since(started); elapsed > time.Second {
		t.Fatalf("dated FIFO rejection blocked for %s", elapsed)
	}
}

func TestDisabledResultUploadStartupRejectsArchiveCountBeyondDescriptorBudget(t *testing.T) {
	dataDir := t.TempDir()
	resultsDir := filepath.Join(dataDir, "results")
	if err := os.Mkdir(resultsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	for index := 0; index <= maximumPersistedResultArchives; index++ {
		name := fmt.Sprintf("%08d.jsonl", index)
		if err := os.WriteFile(filepath.Join(resultsDir, name), nil, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	first := filepath.Join(resultsDir, "00000000.jsonl")
	if err := os.Chmod(resultsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(first, 0o644); err != nil {
		t.Fatal(err)
	}

	authorizer, err := initializeResultUpload(dataDir, "")

	if err == nil || authorizer != nil {
		t.Fatal("result archive count beyond descriptor budget was accepted")
	}
	assertPermissionMode(t, resultsDir, 0o755)
	assertPermissionMode(t, first, 0o644)
}

func TestTrustedDataDirectoryResolvesFilesystemRootWithoutUsingWorkingDirectory(t *testing.T) {
	originalWorkingDirectory, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	workingDirectory := t.TempDir()
	if err := os.Chdir(workingDirectory); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := os.Chdir(originalWorkingDirectory); err != nil {
			t.Errorf("restore working directory: %v", err)
		}
	})

	fd, err := openTrustedDataDirectory(string(filepath.Separator))
	if unix.Geteuid() != 0 {
		if err == nil || fd >= 0 {
			if fd >= 0 {
				_ = unix.Close(fd)
			}
			t.Fatal("non-root service identity unexpectedly accepted root-owned data directory")
		}
		return
	}
	if err != nil {
		t.Fatalf("open filesystem root as data directory: %v", err)
	}
	if fd < 0 {
		t.Fatal("filesystem root was reported missing")
	}
	defer unix.Close(fd)

	var opened unix.Stat_t
	if err := unix.Fstat(fd, &opened); err != nil {
		t.Fatal(err)
	}
	var expected unix.Stat_t
	if err := unix.Stat(string(filepath.Separator), &expected); err != nil {
		t.Fatal(err)
	}
	if opened.Dev != expected.Dev || opened.Ino != expected.Ino {
		t.Fatal("trusted data directory resolved relative to the working directory")
	}
}

func assertPermissionMode(t *testing.T, path string, expected os.FileMode) {
	t.Helper()
	actual := permissionMode(t, path)
	if actual != expected {
		t.Fatalf("mode for %s = %#o, want %#o", filepath.Base(path), actual, expected)
	}
}

func permissionMode(t *testing.T, path string) os.FileMode {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	return info.Mode().Perm()
}
