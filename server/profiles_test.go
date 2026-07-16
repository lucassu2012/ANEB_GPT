package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// 解析仓库内四个真实 profile 不得报错（两端共享合同）。
func TestLoadRealProfiles(t *testing.T) {
	profiles, err := loadProfiles("../profiles")
	if err != nil {
		t.Fatalf("loadProfiles: %v", err)
	}
	want := []string{"basic_network", "s1_chat", "s2_coding_agent", "s3_multimodal"}
	if len(profiles) != len(want) {
		t.Fatalf("got %d profiles, want %d", len(profiles), len(want))
	}
	for _, id := range want {
		p, ok := profiles[id]
		if !ok {
			t.Fatalf("missing profile %s", id)
		}
		if p.Version == "" || p.KpiSet == "" || len(p.Phases) == 0 {
			t.Fatalf("profile %s incompletely parsed: %+v", id, p)
		}
	}
	// s2 有两个 token_stream phase，且 burst 参数解析完整。
	s2 := profiles["s2_coding_agent"]
	ph0, err := s2.tokenStreamPhase(0)
	if err != nil {
		t.Fatal(err)
	}
	if ph0.Tokens != 300 || ph0.Seed != 2001 || ph0.Burst == nil || ph0.Burst.ClusterTps != 100 {
		t.Fatalf("s2 stream phase 0 wrong: %+v", ph0)
	}
	if len(ph0.Burst.PauseMs) != 2 || ph0.Burst.PauseMs[0] != 300 || ph0.Burst.PauseMs[1] != 800 {
		t.Fatalf("s2 burst pause_ms wrong: %+v", ph0.Burst.PauseMs)
	}
	ph1, err := s2.tokenStreamPhase(1)
	if err != nil {
		t.Fatal(err)
	}
	if ph1.Tokens != 800 || ph1.Seed != 2002 {
		t.Fatalf("s2 stream phase 1 wrong: %+v", ph1)
	}
	if _, err := s2.tokenStreamPhase(2); err == nil {
		t.Fatal("expected error for out-of-range token_stream index")
	}
	basic := profiles["basic_network"]
	if basic.ModeID != "network_basic" || basic.Presentation.LiveMetricID != "phase_throughput_mbps" {
		t.Fatalf("basic profile presentation wrong: %+v", basic)
	}
}

func TestProfilesEndpoint(t *testing.T) {
	profiles, err := loadProfiles("../profiles")
	if err != nil {
		t.Fatalf("loadProfiles: %v", err)
	}
	a := &app{profiles: profiles, dataDir: t.TempDir()}
	srv := httptest.NewServer(a.routes())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/v1/profiles")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status %d", resp.StatusCode)
	}
	if got := resp.Header.Get("X-Aneb-Server"); got != serverVersion {
		t.Fatalf("X-Aneb-Server = %q, want %q", got, serverVersion)
	}
	var body struct {
		ServerVersion string     `json:"server_version"`
		Profiles      []*Profile `json:"profiles"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(body.Profiles) != 4 {
		t.Fatalf("got %d profiles", len(body.Profiles))
	}
	for _, p := range body.Profiles {
		if p.ProfileID == "" || p.Version == "" {
			t.Fatalf("profile missing id/version: %+v", p)
		}
	}
}
