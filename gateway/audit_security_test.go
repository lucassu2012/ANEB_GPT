package gateway

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestJSONLAuditorCreatesPrivateLog(t *testing.T) {
	path := filepath.Join(t.TempDir(), "audit.jsonl")
	auditor := &JSONLAuditor{Path: path}
	if err := auditor.Record(AuditEvent{Event: "security-test"}); err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" {
		info, err := os.Stat(path)
		if err != nil {
			t.Fatal(err)
		}
		if info.Mode().Perm() != 0o600 {
			t.Fatalf("audit mode=%#o want=0600", info.Mode().Perm())
		}
	}
}

func TestJSONLAuditorRefusesPreviouslyExposedLog(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows does not enforce Unix permission bits")
	}
	path := filepath.Join(t.TempDir(), "audit.jsonl")
	if err := os.WriteFile(path, []byte("{}\n"), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(path, 0o640); err != nil {
		t.Fatal(err)
	}
	err := (&JSONLAuditor{Path: path}).Record(AuditEvent{Event: "must-not-append"})
	if err == nil || !strings.Contains(err.Error(), "mode 0600") {
		t.Fatalf("error=%v", err)
	}
}
