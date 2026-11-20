package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestLoadProfilesDefensiveErrors 守 loadProfiles 的 fail-closed 分支（profile 是两端共享合同，
// 不允许静默跳过）：重复 profile_id / 缺 profile_id / 缺 version / 解析错。此前仅 happy path
// (TestLoadRealProfiles) 覆盖真实 3 文件，防御分支从未被触发。
func TestLoadProfilesDefensiveErrors(t *testing.T) {
	cases := []struct {
		name  string
		files map[string]string
		want  string // 期望错误信息含的子串
	}{
		{"duplicate profile_id", map[string]string{
			"a.json": `{"profile_id":"dup","version":"1"}`,
			"b.json": `{"profile_id":"dup","version":"2"}`,
		}, "duplicate profile_id"},
		{"missing profile_id", map[string]string{
			"x.json": `{"version":"1"}`,
		}, "missing profile_id or version"},
		{"missing version", map[string]string{
			"x.json": `{"profile_id":"x"}`,
		}, "missing profile_id or version"},
		{"invalid json", map[string]string{
			"x.json": `{bad json`,
		}, "parse"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			dir := t.TempDir()
			for name, content := range c.files {
				if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o600); err != nil {
					t.Fatalf("write %s: %v", name, err)
				}
			}
			_, err := loadProfiles(dir)
			if err == nil {
				t.Fatalf("expected error containing %q, got nil (fail-closed 未生效)", c.want)
			}
			if !strings.Contains(err.Error(), c.want) {
				t.Fatalf("error %q does not contain %q", err.Error(), c.want)
			}
		})
	}
}

// TestArtifactStreamPhaseSelection 守 artifactStreamPhase 的多相选择（只数 artifact_stream，跳过
// 其他相位）与越界报错——此前只有单相 happy case (handlers_artifact_test.go)，未测多相索引/越界。
func TestArtifactStreamPhaseSelection(t *testing.T) {
	p := &Profile{
		ProfileID: "p",
		Phases: []Phase{
			{Type: "artifact_stream", Seed: 10},
			{Type: "download_burst", Bytes: 1024}, // 非 artifact_stream，不计入其索引
			{Type: "artifact_stream", Seed: 20},
		},
	}
	ph0, err := p.artifactStreamPhase(0)
	if err != nil || ph0.Seed != 10 {
		t.Fatalf("idx0: got %+v err=%v, want Seed=10", ph0, err)
	}
	ph1, err := p.artifactStreamPhase(1) // 跳过中间 download_burst → 第 2 个 artifact_stream
	if err != nil || ph1.Seed != 20 {
		t.Fatalf("idx1: got %+v err=%v, want Seed=20（应跳过 download_burst）", ph1, err)
	}
	if _, err := p.artifactStreamPhase(2); err == nil {
		t.Fatal("idx2 越界（仅 2 个 artifact_stream）：期望报错，得 nil")
	}
	if _, err := p.artifactStreamPhase(9); err == nil {
		t.Fatal("idx9 越界：期望报错，得 nil")
	}
}
