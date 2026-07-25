package main

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func validExecutionRequirements() executionRequirements {
	return executionRequirements{
		ContractID: executionRequirementsContractID, ContractVersion: executionRequirementsContractVersion,
		ClientEngine:            executionContractRange{ContractID: tokenClientEngineContractID, MinVersion: "1.0.0", MaxVersionExclusive: "2.0.0"},
		ServerCapabilityReceipt: executionContractRange{ContractID: serverCapabilityReceiptContractID, MinVersion: "1.0.0", MaxVersionExclusive: "2.0.0"},
		RequiredPrimitives: []executionPrimitiveCapability{
			{PrimitiveID: "echo", WireContractID: echoWireContractID},
			{PrimitiveID: "token_sim", WireContractID: tokenSimTaskContract},
			{PrimitiveID: "download", WireContractID: downloadWireContractID},
		},
	}
}

func TestPublishedTokenQuickProducesExactCapabilityReceipt(t *testing.T) {
	receipt, err := loadExecutionCapabilityReceipt("../profiles/published")
	if err != nil {
		t.Fatalf("load execution receipt: %v", err)
	}
	if receipt.ContractID != serverCapabilityReceiptContractID || receipt.ContractVersion != serverCapabilityReceiptVersion {
		t.Fatalf("receipt identity mismatch: %+v", receipt)
	}
	if len(receipt.ValidatedProfiles) != 1 {
		t.Fatalf("validated profiles=%d, want 1", len(receipt.ValidatedProfiles))
	}
	profile := receipt.ValidatedProfiles[0]
	if profile.ProfileID != "token_multimodal_quick" ||
		profile.ProfileVersion != "1.2.1" ||
		profile.ProfileSHA256 != "sha256:caeda36fc11046385fd2ca3052e68d02e4e49ad72ab4125015fd61c91a592773" {
		t.Fatalf("unexpected validated profile: %+v", profile)
	}
}

func TestExecutionRequirementsFailClosed(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*executionRequirements)
		want   string
	}{
		{"unknown wire", func(r *executionRequirements) { r.RequiredPrimitives[0].WireContractID = "unknown-v1" }, "unsupported wire contract"},
		{"duplicate primitive", func(r *executionRequirements) {
			r.RequiredPrimitives = append(r.RequiredPrimitives, r.RequiredPrimitives[0])
		}, "duplicate required primitive"},
		{"missing primitive", func(r *executionRequirements) { r.RequiredPrimitives[0].PrimitiveID = "arbitrary_script" }, "unsupported required primitive"},
		{"client major incompatible", func(r *executionRequirements) {
			r.ClientEngine.MinVersion = "2.0.0"
			r.ClientEngine.MaxVersionExclusive = "3.0.0"
		}, "client engine contract is incompatible"},
		{"receipt major incompatible", func(r *executionRequirements) {
			r.ServerCapabilityReceipt.MinVersion = "2.0.0"
			r.ServerCapabilityReceipt.MaxVersionExclusive = "3.0.0"
		}, "server capability receipt contract is incompatible"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			requirements := validExecutionRequirements()
			test.mutate(&requirements)
			err := validateExecutionRequirements(requirements, builtInExecutionPrimitives())
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("err=%v, want %q", err, test.want)
			}
		})
	}
}

func TestExecutionRequirementsRejectMissingFrozenPrimitive(t *testing.T) {
	requirements := validExecutionRequirements()
	requirements.RequiredPrimitives = requirements.RequiredPrimitives[:2]
	err := validateExecutionRequirements(requirements, builtInExecutionPrimitives())
	if err == nil || !strings.Contains(err.Error(), "does not match Token Quick contract") {
		t.Fatalf("incomplete primitive set accepted: %v", err)
	}
}

func TestServerCapabilitySetRejectsDuplicatePrimitive(t *testing.T) {
	supported := builtInExecutionPrimitives()
	supported = append(supported, supported[0])
	err := validateExecutionRequirements(validExecutionRequirements(), supported)
	if err == nil || !strings.Contains(err.Error(), "duplicate primitive capability") {
		t.Fatalf("duplicate server capability accepted: %v", err)
	}
}

func TestCanonicalJSONMatchesFrozenPythonVectors(t *testing.T) {
	vectors := []struct{ raw, digest string }{
		{`{"tiny":4.2E-5,"edge":0.0001000,"big_fixed":1.0E15,"big_exp":1.0E16,"negative_zero":-0.0,"one":1.00}`, "sha256:c30c678fa35de21db6ad844a97b630aec9c36fd5e63fb619f4b34b0845579109"},
		{`{"variation":-0.0005200000000016303,"residual":4.2000000000541604E-5}`, "sha256:2648099cd155408aa6f7ebd6551d02288f842d466bcafb05449d854dc4307dcd"},
		{"{\"line\":\"x\u2028y\",\"paragraph\":\"a\u2029b\"}", "sha256:1178f2ae2224c3a3a2856c29429279e1924f5408a925cfe29761bb2805e07de4"},
	}
	for _, vector := range vectors {
		got, err := canonicalJSONSHA256([]byte(vector.raw))
		if err != nil {
			t.Fatal(err)
		}
		if got != vector.digest {
			t.Fatalf("digest=%s, want %s", got, vector.digest)
		}
	}
	if _, err := canonicalJSONSHA256([]byte(`{"a":1,"a":2}`)); err == nil || !strings.Contains(err.Error(), "duplicate object key") {
		t.Fatalf("duplicate key accepted: %v", err)
	}
	if _, err := canonicalJSONSHA256([]byte{'{', '"', 'x', '"', ':', '"', 0xff, '"', '}'}); err == nil || !strings.Contains(err.Error(), "UTF-8") {
		t.Fatalf("invalid UTF-8 accepted: %v", err)
	}
}

func TestExecutionProfileManifestMismatchFails(t *testing.T) {
	dir := t.TempDir()
	bundle := filepath.Join(dir, "quick")
	if err := os.Mkdir(bundle, 0o755); err != nil {
		t.Fatal(err)
	}
	requirementsRaw, _ := json.Marshal(validExecutionRequirements())
	profile := publishedExecutionProfile{
		ContractVersion: "aneb-profile-v2", ProfileID: tokenQuickExecutionProfileID,
		Version: tokenQuickExecutionProfileVersion, ModeID: tokenQuickExecutionModeID,
		ExecutionTarget: probeSimulatorExecutionTarget, ClaimScope: probeNodeClaimScope,
		ExecutionRequirements: requirementsRaw,
	}
	raw, _ := json.Marshal(profile)
	if err := os.WriteFile(filepath.Join(bundle, "profile.json"), raw, 0o644); err != nil {
		t.Fatal(err)
	}
	runtimeRaw := []byte(`{"contract_version":"aneb-token-runtime-plan-v1"}`)
	if err := os.WriteFile(filepath.Join(bundle, "runtime_plan.json"), runtimeRaw, 0o644); err != nil {
		t.Fatal(err)
	}
	runtimeDigest := canonicalDigestHex(t, runtimeRaw)
	manifest := strings.Repeat("0", 64) + "  profile.json\n" + runtimeDigest + "  runtime_plan.json\n"
	if err := os.WriteFile(filepath.Join(bundle, "manifest.sha256"), []byte(manifest), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := loadExecutionCapabilityReceipt(dir); err == nil || !strings.Contains(err.Error(), "manifest digest mismatch") {
		t.Fatalf("manifest mismatch accepted: %v", err)
	}
}

func TestExecutionProfileIdentityAndTargetFailClosed(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*publishedExecutionProfile)
	}{
		{"profile id", func(profile *publishedExecutionProfile) { profile.ProfileID = "token_other" }},
		{"profile version", func(profile *publishedExecutionProfile) { profile.Version = "1.3.0" }},
		{"mode", func(profile *publishedExecutionProfile) { profile.ModeID = "network_comprehensive" }},
		{"target", func(profile *publishedExecutionProfile) { profile.ExecutionTarget = "arbitrary_url" }},
		{"claim scope", func(profile *publishedExecutionProfile) { profile.ClaimScope = "internet_end_to_end" }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			dir := t.TempDir()
			bundle := filepath.Join(dir, "quick")
			if err := os.Mkdir(bundle, 0o755); err != nil {
				t.Fatal(err)
			}
			requirementsRaw, _ := json.Marshal(validExecutionRequirements())
			profile := publishedExecutionProfile{
				ContractVersion: "aneb-profile-v2", ProfileID: tokenQuickExecutionProfileID,
				Version: tokenQuickExecutionProfileVersion, ModeID: tokenQuickExecutionModeID,
				ExecutionTarget: probeSimulatorExecutionTarget, ClaimScope: probeNodeClaimScope,
				ExecutionRequirements: requirementsRaw,
			}
			test.mutate(&profile)
			writeSelfConsistentBundle(t, bundle, profile)
			if _, err := loadExecutionCapabilityReceipt(dir); err == nil || !strings.Contains(err.Error(), "unsupported execution profile identity or target") {
				t.Fatalf("mutated profile accepted: %v", err)
			}
		})
	}
}

func TestRuntimeBundleManifestUsesCatalogSyntax(t *testing.T) {
	requirementsRaw, _ := json.Marshal(validExecutionRequirements())
	profile := publishedExecutionProfile{
		ContractVersion: "aneb-profile-v2", ProfileID: tokenQuickExecutionProfileID,
		Version: tokenQuickExecutionProfileVersion, ModeID: tokenQuickExecutionModeID,
		ExecutionTarget: probeSimulatorExecutionTarget, ClaimScope: probeNodeClaimScope,
		ExecutionRequirements: requirementsRaw,
	}
	tests := []struct {
		name   string
		mutate func(string) string
	}{
		{"tab separator", func(manifest string) string { return strings.Replace(manifest, "  profile.json", "\tprofile.json", 1) }},
		{"uppercase digest", func(manifest string) string { return strings.ToUpper(manifest[:64]) + manifest[64:] }},
		{"extra entry", func(manifest string) string { return manifest + strings.Repeat("0", 64) + "  extra.json\n" }},
		{"internal blank line", func(manifest string) string { return strings.Replace(manifest, "\n", "\n\n", 1) }},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			bundle := t.TempDir()
			manifest := writeSelfConsistentBundle(t, bundle, profile)
			if err := os.WriteFile(filepath.Join(bundle, "manifest.sha256"), []byte(test.mutate(manifest)), 0o644); err != nil {
				t.Fatal(err)
			}
			if _, err := verifyRuntimeBundleManifest(bundle); err == nil {
				t.Fatal("non-canonical manifest accepted")
			}
		})
	}
}

func writeSelfConsistentBundle(t *testing.T, bundle string, profile publishedExecutionProfile) string {
	t.Helper()
	profileRaw, err := json.Marshal(profile)
	if err != nil {
		t.Fatal(err)
	}
	runtimeRaw := []byte(`{"contract_version":"aneb-token-runtime-plan-v1"}`)
	if err := os.WriteFile(filepath.Join(bundle, "profile.json"), profileRaw, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(bundle, "runtime_plan.json"), runtimeRaw, 0o644); err != nil {
		t.Fatal(err)
	}
	manifest := canonicalDigestHex(t, profileRaw) + "  profile.json\n" +
		canonicalDigestHex(t, runtimeRaw) + "  runtime_plan.json\n"
	if err := os.WriteFile(filepath.Join(bundle, "manifest.sha256"), []byte(manifest), 0o644); err != nil {
		t.Fatal(err)
	}
	return manifest
}

func canonicalDigestHex(t *testing.T, raw []byte) string {
	t.Helper()
	digest, err := canonicalJSONSHA256(raw)
	if err != nil {
		t.Fatal(err)
	}
	return strings.TrimPrefix(digest, "sha256:")
}

func TestExecutionRequirementsRejectUnknownField(t *testing.T) {
	raw := []byte(`{"contract_id":"aneb-execution-requirements","contract_version":"1.0.0","client_engine":{"contract_id":"aneb-token-simulation-engine","min_version":"1.0.0","max_version_exclusive":"2.0.0"},"server_capability_receipt":{"contract_id":"aneb-server-capability-receipt","min_version":"1.0.0","max_version_exclusive":"2.0.0"},"required_primitives":[],"script":"curl arbitrary"}`)
	var requirements executionRequirements
	err := decodeStrictJSONDocument(raw, &requirements)
	if err == nil || !strings.Contains(err.Error(), "unknown field") {
		t.Fatalf("unknown execution field accepted: %v", err)
	}
}

func TestDeclaredExecutionPrimitivesHaveWorkingHandlers(t *testing.T) {
	server := httptest.NewServer((&app{}).routes())
	defer server.Close()

	echoResp, err := http.Post(server.URL+"/api/v1/echo", "application/octet-stream", strings.NewReader("ping"))
	if err != nil {
		t.Fatal(err)
	}
	echoResp.Body.Close()
	if echoResp.StatusCode != http.StatusOK {
		t.Fatalf("echo status=%d", echoResp.StatusCode)
	}

	downloadResp, err := http.Get(server.URL + "/api/v1/download?bytes=37&chunk_kb=1")
	if err != nil {
		t.Fatal(err)
	}
	downloadBody, err := io.ReadAll(downloadResp.Body)
	downloadResp.Body.Close()
	if err != nil || downloadResp.StatusCode != http.StatusOK || len(downloadBody) != 37 {
		t.Fatalf("download status=%d bytes=%d err=%v", downloadResp.StatusCode, len(downloadBody), err)
	}

	plan := validTokenSimPlan()
	body := tokenSimRequestBody(t, plan, int(plan.UploadPayloadBytes))
	tokenResp, err := http.Post(server.URL+"/api/v1/token-sim", "application/octet-stream", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	tokenBody, err := io.ReadAll(tokenResp.Body)
	tokenResp.Body.Close()
	if err != nil || tokenResp.StatusCode != http.StatusOK || !bytes.Contains(tokenBody, []byte("event: summary")) {
		t.Fatalf("token status=%d err=%v body=%s", tokenResp.StatusCode, err, tokenBody)
	}
}
