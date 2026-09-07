package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"time"
)

const prototypeEvidenceFailureCode = "P018_EVIDENCE_PUBLICATION_FAILED"

const (
	prototypeEvidenceMaxBytes       = 32 << 20
	prototypeEvidenceOutputMaxBytes = 4 << 10
	prototypeEvidenceRuntimeTimeout = 2 * time.Minute
)

var (
	prototypeCampaignIDPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,128}$`)
	prototypeSHA256Pattern     = regexp.MustCompile(`^[a-f0-9]{64}$`)
)

type prototypePublicationReceipt struct {
	CampaignID        string
	ManifestSHA256    string
	PublicationStatus string
	SchemaVersion     string
}

type prototypeEvidenceOutput struct {
	bytes.Buffer
}

func (output *prototypeEvidenceOutput) Write(value []byte) (int, error) {
	remaining := prototypeEvidenceOutputMaxBytes - output.Len()
	if remaining <= 0 {
		return 0, errPrototypeEvidenceRuntimeOutputTooLarge
	}
	if len(value) > remaining {
		written, _ := output.Buffer.Write(value[:remaining])
		return written, errPrototypeEvidenceRuntimeOutputTooLarge
	}
	return output.Buffer.Write(value)
}

func (a *app) handlePrototypeEvidence(w http.ResponseWriter, r *http.Request) {
	if a.prototypeEvidenceRuntime == "" || a.prototypeResultsRoot == "" {
		writePrototypeEvidenceFailure(w, http.StatusServiceUnavailable)
		return
	}
	if r.Method != http.MethodPost {
		writePrototypeEvidenceFailure(w, http.StatusMethodNotAllowed)
		return
	}
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		writePrototypeEvidenceFailure(w, http.StatusUnsupportedMediaType)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, prototypeEvidenceMaxBytes))
	if err != nil {
		status := http.StatusBadRequest
		var maxBytesError *http.MaxBytesError
		if errors.As(err, &maxBytesError) {
			status = http.StatusRequestEntityTooLarge
		}
		writePrototypeEvidenceFailure(w, status)
		return
	}
	if len(body) == 0 {
		writePrototypeEvidenceFailure(w, http.StatusBadRequest)
		return
	}
	var uploadIdentity struct {
		CampaignID string `json:"campaign_id"`
	}
	if err := json.Unmarshal(body, &uploadIdentity); err != nil ||
		!prototypeCampaignIDPattern.MatchString(uploadIdentity.CampaignID) {
		writePrototypeEvidenceFailure(w, http.StatusBadRequest)
		return
	}
	if !a.prototypeEvidenceMu.TryLock() {
		writePrototypeEvidenceFailure(w, http.StatusTooManyRequests)
		return
	}
	defer a.prototypeEvidenceMu.Unlock()

	campaignRoot := filepath.Join(a.prototypeResultsRoot, uploadIdentity.CampaignID)
	if _, err := os.Lstat(campaignRoot); err == nil || !os.IsNotExist(err) {
		writePrototypeEvidenceFailure(w, http.StatusInternalServerError)
		return
	}

	temporary, err := os.CreateTemp("", "aneb-prototype-evidence-*.json")
	if err != nil {
		writePrototypeEvidenceFailure(w, http.StatusInternalServerError)
		return
	}
	temporaryPath := temporary.Name()
	cleanupPending := true
	defer func() {
		if cleanupPending {
			_ = os.Remove(temporaryPath)
		}
	}()
	if _, err := temporary.Write(body); err != nil {
		_ = temporary.Close()
		writePrototypeEvidenceFailure(w, http.StatusInternalServerError)
		return
	}
	if err := temporary.Close(); err != nil {
		writePrototypeEvidenceFailure(w, http.StatusInternalServerError)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), prototypeEvidenceRuntimeTimeout)
	defer cancel()
	cmd := exec.CommandContext(
		ctx,
		a.prototypeEvidenceRuntime,
		"publish-upload",
		"--input", temporaryPath,
		"--output-root", a.prototypeResultsRoot,
	)
	var stdout prototypeEvidenceOutput
	cmd.Stdout = &stdout
	cmd.Stderr = io.Discard
	runtimeErr := cmd.Run()
	cleanupErr := os.Remove(temporaryPath)
	if cleanupErr == nil {
		cleanupPending = false
	}
	if runtimeErr != nil || cleanupErr != nil {
		writePrototypeEvidenceFailure(w, http.StatusInternalServerError)
		return
	}
	receipt, response, err := decodePrototypePublicationReceipt(stdout.Bytes())
	if err != nil || receipt.CampaignID != uploadIdentity.CampaignID {
		writePrototypeEvidenceFailure(w, http.StatusInternalServerError)
		return
	}
	manifestPath := filepath.Join(a.prototypeResultsRoot, receipt.CampaignID, "manifest.json")
	manifestInfo, err := os.Lstat(manifestPath)
	if err != nil || !manifestInfo.Mode().IsRegular() {
		writePrototypeEvidenceFailure(w, http.StatusInternalServerError)
		return
	}
	manifestBytes, err := os.ReadFile(manifestPath)
	if err != nil {
		writePrototypeEvidenceFailure(w, http.StatusInternalServerError)
		return
	}
	manifestSum := sha256.Sum256(manifestBytes)
	if hex.EncodeToString(manifestSum[:]) != receipt.ManifestSHA256 {
		writePrototypeEvidenceFailure(w, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(response)
}

func writePrototypeEvidenceFailure(w http.ResponseWriter, status int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(`{"code":"` + prototypeEvidenceFailureCode + `","ok":false}` + "\n"))
}

func decodePrototypePublicationReceipt(output []byte) (prototypePublicationReceipt, []byte, error) {
	response := output
	line := output
	if bytes.HasSuffix(line, []byte("\n")) {
		line = line[:len(line)-1]
	}
	if len(line) == 0 || bytes.ContainsAny(line, "\r\n") {
		return prototypePublicationReceipt{}, nil, errInvalidPrototypePublicationReceipt
	}
	var compact bytes.Buffer
	if err := json.Compact(&compact, line); err != nil || !bytes.Equal(compact.Bytes(), line) {
		return prototypePublicationReceipt{}, nil, errInvalidPrototypePublicationReceipt
	}
	decoder := json.NewDecoder(bytes.NewReader(line))
	start, err := decoder.Token()
	if err != nil || start != json.Delim('{') {
		return prototypePublicationReceipt{}, nil, errInvalidPrototypePublicationReceipt
	}
	fields := make(map[string]json.RawMessage, 4)
	for decoder.More() {
		keyToken, err := decoder.Token()
		key, ok := keyToken.(string)
		if err != nil || !ok {
			return prototypePublicationReceipt{}, nil, errInvalidPrototypePublicationReceipt
		}
		if _, duplicate := fields[key]; duplicate {
			return prototypePublicationReceipt{}, nil, errInvalidPrototypePublicationReceipt
		}
		var raw json.RawMessage
		if err := decoder.Decode(&raw); err != nil {
			return prototypePublicationReceipt{}, nil, errInvalidPrototypePublicationReceipt
		}
		fields[key] = raw
	}
	end, err := decoder.Token()
	if err != nil || end != json.Delim('}') || len(fields) != 4 {
		return prototypePublicationReceipt{}, nil, errInvalidPrototypePublicationReceipt
	}
	if token, err := decoder.Token(); err != io.EOF || token != nil {
		return prototypePublicationReceipt{}, nil, errInvalidPrototypePublicationReceipt
	}
	for _, key := range []string{"campaign_id", "manifest_sha256", "publication_status", "schema_version"} {
		if _, ok := fields[key]; !ok {
			return prototypePublicationReceipt{}, nil, errInvalidPrototypePublicationReceipt
		}
	}
	var receipt prototypePublicationReceipt
	if json.Unmarshal(fields["campaign_id"], &receipt.CampaignID) != nil ||
		json.Unmarshal(fields["manifest_sha256"], &receipt.ManifestSHA256) != nil ||
		json.Unmarshal(fields["publication_status"], &receipt.PublicationStatus) != nil ||
		json.Unmarshal(fields["schema_version"], &receipt.SchemaVersion) != nil ||
		!prototypeCampaignIDPattern.MatchString(receipt.CampaignID) ||
		!prototypeSHA256Pattern.MatchString(receipt.ManifestSHA256) ||
		receipt.PublicationStatus != "verified" ||
		receipt.SchemaVersion != "aneb-prototype-publication-receipt-0.1" {
		return prototypePublicationReceipt{}, nil, errInvalidPrototypePublicationReceipt
	}
	return receipt, response, nil
}

var (
	errInvalidPrototypePublicationReceipt     = errors.New("invalid Prototype evidence publication receipt")
	errPrototypeEvidenceRuntimeOutputTooLarge = errors.New("Prototype evidence runtime output exceeded limit")
)
