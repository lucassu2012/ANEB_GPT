package gateway

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"regexp"
	"strings"
)

type API struct {
	Manager             *Manager
	Token               string
	AllowedClientSubnet *net.IPNet
}

var bearerTokenPattern = regexp.MustCompile(`^[A-Fa-f0-9]{64}$`)

func ValidBearerToken(value string) bool {
	return bearerTokenPattern.MatchString(value)
}

func (a API) Handler() (http.Handler, error) {
	if a.Manager == nil {
		return nil, fmt.Errorf("manager is required")
	}
	if !ValidBearerToken(a.Token) {
		return nil, fmt.Errorf("gateway token must be exactly 32 random bytes encoded as 64 hex characters")
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", a.health)
	mux.Handle("/v1/profiles", a.auth(http.HandlerFunc(a.profiles)))
	mux.Handle("/v1/status", a.auth(http.HandlerFunc(a.status)))
	mux.Handle("/v1/experiments", a.auth(http.HandlerFunc(a.experiments)))
	mux.Handle("/v1/experiments/", a.auth(http.HandlerFunc(a.experiment)))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Aneb-Gateway", GatewayVersion)
		w.Header().Set("Cache-Control", "no-store")
		if a.AllowedClientSubnet != nil && !remoteAllowed(r.RemoteAddr, a.AllowedClientSubnet) {
			writeError(w, http.StatusForbidden, "client_outside_exclusive_subnet")
			return
		}
		mux.ServeHTTP(w, r)
	}), nil
}

func (a API) auth(next http.Handler) http.Handler {
	expected := sha256.Sum256([]byte("Bearer " + a.Token))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		actual := sha256.Sum256([]byte(r.Header.Get("Authorization")))
		if subtle.ConstantTimeCompare(actual[:], expected[:]) != 1 {
			w.Header().Set("WWW-Authenticate", "Bearer")
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (a API) health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	status := "ready"
	statusCode := http.StatusOK
	if active := a.Manager.Status(); active != nil && active.Phase == "cleanup_failed" {
		status = "degraded_cleanup_failed"
		statusCode = http.StatusServiceUnavailable
	}
	writeJSON(w, statusCode, map[string]any{
		"status": status, "version": GatewayVersion,
		"impairment_layer": "ip_forwarding", "radio_impairment": false,
	})
}

func remoteAllowed(remote string, network *net.IPNet) bool {
	host, _, err := net.SplitHostPort(remote)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && network.Contains(ip)
}

func (a API) profiles(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	type catalogEntry struct {
		Profile     Profile `json:"profile"`
		Fingerprint string  `json:"fingerprint"`
	}
	profiles := a.Manager.Profiles()
	entries := make([]catalogEntry, 0, len(profiles))
	for _, profile := range profiles {
		entries = append(entries, catalogEntry{Profile: profile, Fingerprint: profile.Fingerprint()})
	}
	writeJSON(w, http.StatusOK, map[string]any{"profiles": entries})
}

func (a API) status(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"version": GatewayVersion, "active_experiment": a.Manager.Status(),
		"impairment_layer": "ip_forwarding", "radio_impairment": false,
	})
}

func (a API) experiments(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	var request struct {
		RunID      string `json:"run_id"`
		ProfileRef string `json:"profile_ref"`
	}
	if err := decodeJSON(w, r, &request); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	experiment, err := a.Manager.Start(request.RunID, request.ProfileRef)
	if err != nil {
		switch {
		case errors.Is(err, ErrCleanupLatched):
			writeError(w, http.StatusLocked, "cleanup_failed_latched")
		case errors.Is(err, ErrExperimentActive):
			writeError(w, http.StatusConflict, "experiment_active")
		case errors.Is(err, ErrRunConflict):
			writeError(w, http.StatusConflict, "run_id_profile_conflict")
		case errors.Is(err, ErrNotFound):
			writeError(w, http.StatusNotFound, "profile_not_found")
		default:
			writeError(w, http.StatusBadRequest, err.Error())
		}
		return
	}
	writeJSON(w, http.StatusAccepted, experiment)
}

func (a API) experiment(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/v1/experiments/")
	if id == "" || strings.Contains(id, "/") {
		writeError(w, http.StatusNotFound, "experiment_not_found")
		return
	}
	switch r.Method {
	case http.MethodGet:
		experiment, err := a.Manager.Get(id)
		if err != nil {
			writeError(w, http.StatusNotFound, "experiment_not_found")
			return
		}
		writeJSON(w, http.StatusOK, experiment)
	case http.MethodDelete:
		experiment, err := a.Manager.Stop(id)
		if err != nil {
			writeError(w, http.StatusNotFound, "experiment_not_found")
			return
		}
		writeJSON(w, http.StatusAccepted, experiment)
	default:
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
	}
}

func decodeJSON(w http.ResponseWriter, r *http.Request, target any) error {
	r.Body = http.MaxBytesReader(w, r.Body, 4096)
	defer r.Body.Close()
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(target); err != nil {
		return err
	}
	var extra any
	if err := dec.Decode(&extra); !errors.Is(err, io.EOF) {
		return fmt.Errorf("request must contain one JSON object")
	}
	return nil
}

func writeError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
