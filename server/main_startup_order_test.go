package main

import (
	"os"
	"strings"
	"testing"
)

func TestResultStorageHardeningRunsOnlyAfterReadOnlyConfigurationValidation(t *testing.T) {
	source, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatal(err)
	}
	text := string(source)
	resultStorage := strings.Index(text, "initializeResultUpload(*dataDir, *resultUploadTokenFile)")
	appConstruction := strings.Index(text, "a := &app{")
	if resultStorage < 0 || appConstruction < 0 {
		t.Fatal("result-upload startup wiring was not found")
	}
	for _, validation := range []string{
		"validateH3Prereqs(*tlsCert, *tlsKey)",
		"validateTLSFiles(*tlsCert, *tlsKey)",
		"validateIPCertPair(*tlsCertIP, *tlsKeyIP)",
		"loadProfiles(*profilesDir)",
		"loadExecutionCapabilityReceipt(*executionProfilesDir)",
		"validateExecutionRuntimeConfig(executionCapabilities, *addr, *udpEchoAddr)",
	} {
		position := strings.Index(text, validation)
		if position < 0 {
			t.Fatalf("startup validation was not found: %s", validation)
		}
		if position > resultStorage {
			t.Fatalf("startup validation runs after result storage mutation: %s", validation)
		}
	}
	if resultStorage > appConstruction {
		t.Fatal("result-upload startup wiring runs after app construction")
	}
}

func TestStorageHardeningOnlyModeExitsBeforeApplicationOrListenerConstruction(t *testing.T) {
	source, err := os.ReadFile("main.go")
	if err != nil {
		t.Fatal(err)
	}
	text := string(source)
	flagDeclaration := strings.Index(text, `"harden-result-storage-only"`)
	resultStorage := strings.Index(text, "initializeResultUpload(*dataDir, *resultUploadTokenFile)")
	earlyExit := strings.Index(text, "if *hardenResultStorageOnly {")
	appConstruction := strings.Index(text, "a := &app{")
	listener := strings.Index(text, "srv := &http.Server{")
	for name, position := range map[string]int{
		"flag":           flagDeclaration,
		"result storage": resultStorage,
		"early exit":     earlyExit,
		"app":            appConstruction,
		"listener":       listener,
	} {
		if position < 0 {
			t.Fatalf("%s wiring was not found", name)
		}
	}
	if !(flagDeclaration < resultStorage && resultStorage < earlyExit &&
		earlyExit < appConstruction && appConstruction < listener) {
		t.Fatal("hardening-only mode can reach application or listener construction")
	}
}
