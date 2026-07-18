package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"math/big"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"
)

const (
	executionRequirementsContractID      = "aneb-execution-requirements"
	executionRequirementsContractVersion = "1.0.0"
	tokenClientEngineContractID          = "aneb-token-simulation-engine"
	tokenClientEngineContractVersion     = "1.0.0"
	serverCapabilityReceiptContractID    = "aneb-server-capability-receipt"
	serverCapabilityReceiptVersion       = "1.0.0"
	echoWireContractID                   = "aneb-echo-v1"
	downloadWireContractID               = "aneb-download-v1"
	tokenQuickExecutionProfileID         = "token_multimodal_quick"
	tokenQuickExecutionProfileVersion    = "1.2.1"
	tokenQuickExecutionModeID            = "token_simulation"
	probeSimulatorExecutionTarget        = "aneb_probe_simulator"
	probeNodeClaimScope                  = "application_end_to_end_to_probe_node"
)

var strictManifestLine = regexp.MustCompile(`^([0-9a-f]{64})  ([A-Za-z0-9_.-]+)$`)

type executionContractRange struct {
	ContractID          string `json:"contract_id"`
	MinVersion          string `json:"min_version"`
	MaxVersionExclusive string `json:"max_version_exclusive"`
}

type executionPrimitiveCapability struct {
	PrimitiveID    string `json:"primitive_id"`
	WireContractID string `json:"wire_contract_id"`
}

type executionRequirements struct {
	ContractID              string                         `json:"contract_id"`
	ContractVersion         string                         `json:"contract_version"`
	ClientEngine            executionContractRange         `json:"client_engine"`
	ServerCapabilityReceipt executionContractRange         `json:"server_capability_receipt"`
	RequiredPrimitives      []executionPrimitiveCapability `json:"required_primitives"`
}

type validatedExecutionProfile struct {
	ProfileID      string `json:"profile_id"`
	ProfileVersion string `json:"profile_version"`
	ProfileSHA256  string `json:"profile_sha256"`
}

type serverCapabilityReceipt struct {
	ContractID        string                         `json:"contract_id"`
	ContractVersion   string                         `json:"contract_version"`
	Primitives        []executionPrimitiveCapability `json:"primitives"`
	ValidatedProfiles []validatedExecutionProfile    `json:"validated_profiles"`
}

type publishedExecutionProfile struct {
	ContractVersion       string          `json:"contract_version"`
	ProfileID             string          `json:"profile_id"`
	Version               string          `json:"version"`
	ModeID                string          `json:"mode_id"`
	ExecutionTarget       string          `json:"execution_target"`
	ClaimScope            string          `json:"claim_scope"`
	ExecutionRequirements json.RawMessage `json:"execution_requirements"`
}

func builtInExecutionPrimitives() []executionPrimitiveCapability {
	return []executionPrimitiveCapability{
		{PrimitiveID: "download", WireContractID: downloadWireContractID},
		{PrimitiveID: "echo", WireContractID: echoWireContractID},
		{PrimitiveID: "token_sim", WireContractID: tokenSimTaskContract},
	}
}

func baseServerCapabilityReceipt() serverCapabilityReceipt {
	return serverCapabilityReceipt{
		ContractID:        serverCapabilityReceiptContractID,
		ContractVersion:   serverCapabilityReceiptVersion,
		Primitives:        builtInExecutionPrimitives(),
		ValidatedProfiles: []validatedExecutionProfile{},
	}
}

// loadExecutionCapabilityReceipt validates every published profile that opts in to
// execution_requirements. A server that cannot prove at least one exact profile is
// supported must not start as aneb-server/0.8.x.
func loadExecutionCapabilityReceipt(dir string) (serverCapabilityReceipt, error) {
	receipt := baseServerCapabilityReceipt()
	entries, err := os.ReadDir(dir)
	if err != nil {
		return receipt, fmt.Errorf("read execution profiles dir %s: %w", dir, err)
	}
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		bundleDir := filepath.Join(dir, entry.Name())
		profilePath := filepath.Join(bundleDir, "profile.json")
		raw, err := os.ReadFile(profilePath)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return receipt, fmt.Errorf("read %s: %w", profilePath, err)
		}
		profileDigest, err := canonicalJSONSHA256(raw)
		if err != nil {
			return receipt, fmt.Errorf("canonicalize %s: %w", profilePath, err)
		}
		var profile publishedExecutionProfile
		if err := json.Unmarshal(raw, &profile); err != nil {
			return receipt, fmt.Errorf("parse %s: %w", profilePath, err)
		}
		if len(profile.ExecutionRequirements) == 0 {
			continue
		}
		if profile.ContractVersion != "aneb-profile-v2" ||
			profile.ProfileID != tokenQuickExecutionProfileID ||
			profile.Version != tokenQuickExecutionProfileVersion ||
			profile.ModeID != tokenQuickExecutionModeID ||
			profile.ExecutionTarget != probeSimulatorExecutionTarget ||
			profile.ClaimScope != probeNodeClaimScope {
			return receipt, fmt.Errorf("%s: unsupported execution profile identity or target", profilePath)
		}
		if _, err := parseStrictSemver(profile.Version); err != nil {
			return receipt, fmt.Errorf("%s: invalid profile version: %w", profilePath, err)
		}
		var requirements executionRequirements
		if err := decodeStrictJSONDocument(profile.ExecutionRequirements, &requirements); err != nil {
			return receipt, fmt.Errorf("%s: invalid execution_requirements: %w", profilePath, err)
		}
		if err := validateExecutionRequirements(requirements, receipt.Primitives); err != nil {
			return receipt, fmt.Errorf("%s: %w", profilePath, err)
		}
		manifestDigest, err := verifyRuntimeBundleManifest(bundleDir)
		if err != nil {
			return receipt, fmt.Errorf("%s: %w", profilePath, err)
		}
		if profileDigest != manifestDigest {
			return receipt, fmt.Errorf("%s: profile manifest digest mismatch", profilePath)
		}
		receipt.ValidatedProfiles = append(receipt.ValidatedProfiles, validatedExecutionProfile{
			ProfileID: profile.ProfileID, ProfileVersion: profile.Version, ProfileSHA256: profileDigest,
		})
	}
	if len(receipt.ValidatedProfiles) == 0 {
		return receipt, fmt.Errorf("no published profile declares execution_requirements")
	}
	sort.Slice(receipt.ValidatedProfiles, func(i, j int) bool {
		if receipt.ValidatedProfiles[i].ProfileID == receipt.ValidatedProfiles[j].ProfileID {
			return receipt.ValidatedProfiles[i].ProfileVersion < receipt.ValidatedProfiles[j].ProfileVersion
		}
		return receipt.ValidatedProfiles[i].ProfileID < receipt.ValidatedProfiles[j].ProfileID
	})
	for i := 1; i < len(receipt.ValidatedProfiles); i++ {
		if receipt.ValidatedProfiles[i-1].ProfileID == receipt.ValidatedProfiles[i].ProfileID {
			return receipt, fmt.Errorf("duplicate execution profile_id %q", receipt.ValidatedProfiles[i].ProfileID)
		}
	}
	return receipt, nil
}

func decodeStrictJSONDocument(raw []byte, destination any) error {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		if err == nil {
			return fmt.Errorf("trailing JSON data")
		}
		return err
	}
	return nil
}

func validateExecutionRequirements(req executionRequirements, supported []executionPrimitiveCapability) error {
	if req.ContractID != executionRequirementsContractID || req.ContractVersion != executionRequirementsContractVersion {
		return fmt.Errorf("unsupported execution requirements contract")
	}
	if req.ClientEngine.ContractID != tokenClientEngineContractID ||
		!versionInRange(tokenClientEngineContractVersion, req.ClientEngine) {
		return fmt.Errorf("client engine contract is incompatible")
	}
	if req.ServerCapabilityReceipt.ContractID != serverCapabilityReceiptContractID ||
		!versionInRange(serverCapabilityReceiptVersion, req.ServerCapabilityReceipt) {
		return fmt.Errorf("server capability receipt contract is incompatible")
	}
	if len(req.RequiredPrimitives) == 0 {
		return fmt.Errorf("required_primitives is empty")
	}
	available := make(map[string]string, len(supported))
	for _, capability := range supported {
		if capability.PrimitiveID == "" || capability.WireContractID == "" {
			return fmt.Errorf("server contains an empty primitive capability")
		}
		if _, duplicate := available[capability.PrimitiveID]; duplicate {
			return fmt.Errorf("server contains duplicate primitive capability %q", capability.PrimitiveID)
		}
		available[capability.PrimitiveID] = capability.WireContractID
	}
	seen := make(map[string]struct{}, len(req.RequiredPrimitives))
	expected := map[string]string{
		"download":  downloadWireContractID,
		"echo":      echoWireContractID,
		"token_sim": tokenSimTaskContract,
	}
	for _, required := range req.RequiredPrimitives {
		if _, duplicate := seen[required.PrimitiveID]; duplicate {
			return fmt.Errorf("duplicate required primitive %q", required.PrimitiveID)
		}
		seen[required.PrimitiveID] = struct{}{}
		wire, ok := available[required.PrimitiveID]
		if !ok {
			return fmt.Errorf("unsupported required primitive %q", required.PrimitiveID)
		}
		if wire != required.WireContractID {
			return fmt.Errorf("unsupported wire contract %q for primitive %q", required.WireContractID, required.PrimitiveID)
		}
		expectedWire, expectedPrimitive := expected[required.PrimitiveID]
		if !expectedPrimitive || expectedWire != required.WireContractID {
			return fmt.Errorf("required primitive set does not match Token Quick contract")
		}
	}
	if len(seen) != len(expected) {
		return fmt.Errorf("required primitive set does not match Token Quick contract")
	}
	return nil
}

type strictSemver struct{ major, minor, patch uint64 }

func parseStrictSemver(raw string) (strictSemver, error) {
	parts := strings.Split(raw, ".")
	if len(parts) != 3 {
		return strictSemver{}, fmt.Errorf("expected MAJOR.MINOR.PATCH")
	}
	values := [3]uint64{}
	for i, part := range parts {
		if part == "" || (len(part) > 1 && part[0] == '0') {
			return strictSemver{}, fmt.Errorf("invalid numeric component %q", part)
		}
		value, err := strconv.ParseUint(part, 10, 64)
		if err != nil {
			return strictSemver{}, fmt.Errorf("invalid numeric component %q", part)
		}
		values[i] = value
	}
	return strictSemver{values[0], values[1], values[2]}, nil
}

func (v strictSemver) compare(other strictSemver) int {
	left := [3]uint64{v.major, v.minor, v.patch}
	right := [3]uint64{other.major, other.minor, other.patch}
	for i := range left {
		if left[i] < right[i] {
			return -1
		}
		if left[i] > right[i] {
			return 1
		}
	}
	return 0
}

func versionInRange(version string, contract executionContractRange) bool {
	v, err := parseStrictSemver(version)
	if err != nil {
		return false
	}
	min, err := parseStrictSemver(contract.MinVersion)
	if err != nil {
		return false
	}
	max, err := parseStrictSemver(contract.MaxVersionExclusive)
	if err != nil || min.compare(max) >= 0 {
		return false
	}
	return v.compare(min) >= 0 && v.compare(max) < 0
}

func verifyRuntimeBundleManifest(bundleDir string) (string, error) {
	path := filepath.Join(bundleDir, "manifest.sha256")
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("read manifest: %w", err)
	}
	if !utf8.Valid(raw) {
		return "", fmt.Errorf("manifest is not valid UTF-8")
	}
	text := strings.ReplaceAll(string(raw), "\r\n", "\n")
	if strings.ContainsRune(text, '\r') {
		return "", fmt.Errorf("manifest contains a bare carriage return")
	}
	lines := strings.Split(text, "\n")
	if len(lines) > 0 && lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1]
	}
	if len(lines) == 0 {
		return "", fmt.Errorf("empty manifest")
	}
	declared := make(map[string]string, len(lines))
	for index, line := range lines {
		match := strictManifestLine.FindStringSubmatch(line)
		if match == nil {
			return "", fmt.Errorf("manifest line %d must use '<lowercase sha256><two spaces><basename>'", index+1)
		}
		digest, name := match[1], match[2]
		if _, duplicate := declared[name]; duplicate {
			return "", fmt.Errorf("duplicate manifest entry %q", name)
		}
		declared[name] = digest
	}
	if len(declared) != 2 || declared["profile.json"] == "" || declared["runtime_plan.json"] == "" {
		return "", fmt.Errorf("manifest must contain exactly profile.json and runtime_plan.json")
	}
	for _, name := range []string{"profile.json", "runtime_plan.json"} {
		assetRaw, err := os.ReadFile(filepath.Join(bundleDir, name))
		if err != nil {
			return "", fmt.Errorf("read manifest asset %s: %w", name, err)
		}
		actual, err := canonicalJSONSHA256(assetRaw)
		if err != nil {
			return "", fmt.Errorf("canonicalize manifest asset %s: %w", name, err)
		}
		if actual != "sha256:"+declared[name] {
			return "", fmt.Errorf("%s manifest digest mismatch", name)
		}
	}
	return "sha256:" + declared["profile.json"], nil
}

func canonicalJSONSHA256(raw []byte) (string, error) {
	if !utf8.Valid(raw) {
		return "", fmt.Errorf("JSON is not valid UTF-8")
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	value, err := decodeCanonicalJSONValue(decoder)
	if err != nil {
		return "", err
	}
	if _, err := decoder.Token(); !errors.Is(err, io.EOF) {
		if err == nil {
			return "", fmt.Errorf("trailing JSON data")
		}
		return "", err
	}
	var canonical bytes.Buffer
	if err := writeCanonicalJSON(&canonical, value); err != nil {
		return "", err
	}
	digest := sha256.Sum256(canonical.Bytes())
	return "sha256:" + hex.EncodeToString(digest[:]), nil
}

func decodeCanonicalJSONValue(decoder *json.Decoder) (any, error) {
	token, err := decoder.Token()
	if err != nil {
		return nil, err
	}
	delim, isDelim := token.(json.Delim)
	if !isDelim {
		return token, nil
	}
	switch delim {
	case '{':
		object := map[string]any{}
		for decoder.More() {
			keyToken, err := decoder.Token()
			if err != nil {
				return nil, err
			}
			key, ok := keyToken.(string)
			if !ok {
				return nil, fmt.Errorf("object key is not a string")
			}
			if _, duplicate := object[key]; duplicate {
				return nil, fmt.Errorf("duplicate object key %q", key)
			}
			value, err := decodeCanonicalJSONValue(decoder)
			if err != nil {
				return nil, err
			}
			object[key] = value
		}
		if end, err := decoder.Token(); err != nil || end != json.Delim('}') {
			return nil, fmt.Errorf("unterminated object")
		}
		return object, nil
	case '[':
		array := []any{}
		for decoder.More() {
			value, err := decodeCanonicalJSONValue(decoder)
			if err != nil {
				return nil, err
			}
			array = append(array, value)
		}
		if end, err := decoder.Token(); err != nil || end != json.Delim(']') {
			return nil, fmt.Errorf("unterminated array")
		}
		return array, nil
	default:
		return nil, fmt.Errorf("unexpected JSON delimiter %q", delim)
	}
}

func writeCanonicalJSON(out *bytes.Buffer, value any) error {
	switch typed := value.(type) {
	case nil:
		out.WriteString("null")
	case bool:
		out.WriteString(strconv.FormatBool(typed))
	case string:
		var encoded bytes.Buffer
		encoder := json.NewEncoder(&encoded)
		encoder.SetEscapeHTML(false)
		if err := encoder.Encode(typed); err != nil {
			return err
		}
		canonicalString := bytes.TrimSuffix(encoded.Bytes(), []byte("\n"))
		// encoding/json escapes these two code points for JSONP safety even
		// with SetEscapeHTML(false); Python ensure_ascii=False, which defines
		// canonical-json-sha256-v1, emits their UTF-8 bytes unchanged.
		canonicalString = bytes.ReplaceAll(canonicalString, []byte(`\u2028`), []byte("\u2028"))
		canonicalString = bytes.ReplaceAll(canonicalString, []byte(`\u2029`), []byte("\u2029"))
		out.Write(canonicalString)
	case json.Number:
		canonical, err := canonicalJSONNumber(string(typed))
		if err != nil {
			return err
		}
		out.WriteString(canonical)
	case []any:
		out.WriteByte('[')
		for i, item := range typed {
			if i > 0 {
				out.WriteByte(',')
			}
			if err := writeCanonicalJSON(out, item); err != nil {
				return err
			}
		}
		out.WriteByte(']')
	case map[string]any:
		keys := make([]string, 0, len(typed))
		for key := range typed {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		out.WriteByte('{')
		for i, key := range keys {
			if i > 0 {
				out.WriteByte(',')
			}
			if err := writeCanonicalJSON(out, key); err != nil {
				return err
			}
			out.WriteByte(':')
			if err := writeCanonicalJSON(out, typed[key]); err != nil {
				return err
			}
		}
		out.WriteByte('}')
	default:
		return fmt.Errorf("unsupported canonical JSON value %T", value)
	}
	return nil
}

func canonicalJSONNumber(raw string) (string, error) {
	if !strings.ContainsAny(raw, ".eE") {
		integer := new(big.Int)
		if _, ok := integer.SetString(raw, 10); !ok {
			return "", fmt.Errorf("invalid integer %q", raw)
		}
		return integer.String(), nil
	}
	value, err := strconv.ParseFloat(raw, 64)
	if err != nil || math.IsInf(value, 0) || math.IsNaN(value) {
		return "", fmt.Errorf("invalid float %q", raw)
	}
	if value == 0 {
		if math.Signbit(value) {
			return "-0.0", nil
		}
		return "0.0", nil
	}
	sign := ""
	if value < 0 {
		sign = "-"
		value = -value
	}
	plain := strconv.FormatFloat(value, 'f', -1, 64)
	parts := strings.SplitN(plain, ".", 2)
	integerPart := parts[0]
	fractionPart := ""
	if len(parts) == 2 {
		fractionPart = parts[1]
	}
	exponent := 0
	digits := ""
	if index := strings.IndexAny(integerPart, "123456789"); index >= 0 {
		exponent = len(integerPart) - index - 1
		digits = integerPart[index:] + fractionPart
	} else {
		index := strings.IndexAny(fractionPart, "123456789")
		if index < 0 {
			return "", fmt.Errorf("invalid zero float %q", raw)
		}
		exponent = -(index + 1)
		digits = fractionPart[index:]
	}
	digits = strings.TrimRight(digits, "0")
	if exponent < -4 || exponent >= 16 {
		mantissa := digits[:1]
		if len(digits) > 1 {
			mantissa += "." + digits[1:]
		}
		exponentSign := "+"
		if exponent < 0 {
			exponentSign = "-"
			exponent = -exponent
		}
		return fmt.Sprintf("%s%se%s%02d", sign, mantissa, exponentSign, exponent), nil
	}
	if !strings.Contains(plain, ".") {
		plain += ".0"
	}
	return sign + plain, nil
}
