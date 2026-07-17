package gateway

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"sync"
	"time"
)

const GatewayVersion = "aneb-gateway/0.2.0"

var runIDPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,128}$`)

var (
	ErrExperimentActive = errors.New("an experiment is already active")
	ErrNotFound         = errors.New("experiment not found")
	ErrCleanupLatched   = errors.New("gateway cleanup failure is latched")
	ErrRunConflict      = errors.New("run_id is already bound to another profile")
)

type ImpairmentController interface {
	Apply(context.Context, Profile) error
	Clear(context.Context) error
}

type Experiment struct {
	ExperimentID       string     `json:"experiment_id"`
	RunID              string     `json:"run_id"`
	ProfileRef         string     `json:"profile_ref"`
	ProfileFingerprint string     `json:"profile_fingerprint"`
	Phase              string     `json:"phase"`
	CreatedAt          time.Time  `json:"created_at"`
	ScheduledAt        time.Time  `json:"scheduled_at"`
	ExpectedActiveAt   time.Time  `json:"expected_active_at"`
	ActiveAt           *time.Time `json:"active_at,omitempty"`
	ExpectedClearAt    *time.Time `json:"expected_clear_at,omitempty"`
	ClearedAt          *time.Time `json:"cleared_at,omitempty"`
	CleanupVerified    bool       `json:"cleanup_verified"`
	StopReason         string     `json:"stop_reason,omitempty"`
	Error              string     `json:"error,omitempty"`
	ClaimScope         string     `json:"claim_scope"`
	ImpairmentLayer    string     `json:"impairment_layer"`
	finalPhase         string
	finalError         string
}

type AuditEvent struct {
	GatewayVersion string     `json:"gateway_version"`
	Event          string     `json:"event"`
	RecordedAt     time.Time  `json:"recorded_at"`
	Experiment     Experiment `json:"experiment"`
}

type Auditor interface {
	Record(AuditEvent) error
}

type JSONLAuditor struct {
	Path string
	mu   sync.Mutex
}

const maxAuditBytes = 64 << 20

func (a *JSONLAuditor) Record(event AuditEvent) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if err := os.MkdirAll(filepath.Dir(a.Path), 0o750); err != nil {
		return err
	}
	if info, err := os.Lstat(a.Path); err == nil {
		if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
			return fmt.Errorf("audit path must be a regular non-symlink file")
		}
		if runtime.GOOS != "windows" && info.Mode().Perm() != 0o600 {
			return fmt.Errorf("audit path must have mode 0600")
		}
		if info.Size() >= maxAuditBytes {
			return fmt.Errorf("audit log reached the %d byte safety limit", maxAuditBytes)
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	f, err := os.OpenFile(a.Path, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	enc := json.NewEncoder(f)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(event); err != nil {
		_ = f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		return err
	}
	return f.Close()
}

type Manager struct {
	mu          sync.Mutex
	cleanupMu   sync.Mutex
	profiles    map[string]Profile
	controller  ImpairmentController
	auditor     Auditor
	ctx         context.Context
	cancel      context.CancelFunc
	wg          sync.WaitGroup
	experiments map[string]*Experiment
	byRunID     map[string]string
	activeID    string
	activeStop  context.CancelFunc
	closed      bool
}

func NewManager(ctx context.Context, profiles map[string]Profile, controller ImpairmentController, auditor Auditor) (*Manager, error) {
	if len(profiles) == 0 {
		return nil, fmt.Errorf("profiles are required")
	}
	if controller == nil || auditor == nil {
		return nil, fmt.Errorf("controller and auditor are required")
	}
	for ref, profile := range profiles {
		if ref != profile.Ref() {
			return nil, fmt.Errorf("profile map key %q does not match %q", ref, profile.Ref())
		}
		if err := profile.Validate(); err != nil {
			return nil, fmt.Errorf("profile %s: %w", ref, err)
		}
	}
	// Fail-safe startup cleanup: a process crash must not leave a stale qdisc.
	cleanupCtx, cleanupCancel := context.WithTimeout(ctx, 10*time.Second)
	err := controller.Clear(cleanupCtx)
	cleanupCancel()
	if err != nil {
		return nil, fmt.Errorf("startup cleanup: %w", err)
	}
	managerCtx, cancel := context.WithCancel(ctx)
	return &Manager{
		profiles:    profiles,
		controller:  controller,
		auditor:     auditor,
		ctx:         managerCtx,
		cancel:      cancel,
		experiments: make(map[string]*Experiment),
		byRunID:     make(map[string]string),
	}, nil
}

func (m *Manager) Profiles() []Profile {
	m.mu.Lock()
	defer m.mu.Unlock()
	result := make([]Profile, 0, len(m.profiles))
	for _, profile := range m.profiles {
		result = append(result, profile)
	}
	sortProfiles(result)
	return result
}

func sortProfiles(profiles []Profile) {
	sort.Slice(profiles, func(i, j int) bool { return profiles[i].Ref() < profiles[j].Ref() })
}

func (m *Manager) Start(runID, profileRef string) (Experiment, error) {
	if !runIDPattern.MatchString(runID) {
		return Experiment{}, fmt.Errorf("invalid run_id")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return Experiment{}, fmt.Errorf("manager is closed")
	}
	if existingID := m.byRunID[runID]; existingID != "" {
		existing := m.experiments[existingID]
		if existing == nil {
			return Experiment{}, fmt.Errorf("run_id index is inconsistent")
		}
		if existing.ProfileRef != profileRef {
			return Experiment{}, ErrRunConflict
		}
		return *existing, nil
	}
	profile, ok := m.profiles[profileRef]
	if !ok {
		return Experiment{}, fmt.Errorf("%w: profile %s", ErrNotFound, profileRef)
	}
	if m.activeID != "" {
		if active := m.experiments[m.activeID]; active != nil && active.Phase == "cleanup_failed" {
			return Experiment{}, ErrCleanupLatched
		}
		return Experiment{}, ErrExperimentActive
	}
	id, err := randomID()
	if err != nil {
		return Experiment{}, err
	}
	now := time.Now().UTC()
	experiment := &Experiment{
		ExperimentID:       id,
		RunID:              runID,
		ProfileRef:         profile.Ref(),
		ProfileFingerprint: profile.Fingerprint(),
		Phase:              "scheduled",
		CreatedAt:          now,
		ScheduledAt:        now,
		ExpectedActiveAt:   now.Add(time.Duration(profile.ActivationDelayMs) * time.Millisecond),
		ClaimScope:         profile.ClaimScope,
		ImpairmentLayer:    profile.ImpairmentLayer,
	}
	m.experiments[id] = experiment
	m.byRunID[runID] = id
	m.activeID = id
	experimentCtx, stop := context.WithCancel(m.ctx)
	m.activeStop = stop
	if err := m.recordLocked("scheduled", experiment); err != nil {
		delete(m.experiments, id)
		delete(m.byRunID, runID)
		m.activeID = ""
		m.activeStop = nil
		stop()
		return Experiment{}, fmt.Errorf("write scheduled audit: %w", err)
	}
	m.wg.Add(1)
	go m.run(experimentCtx, id, profile)
	return *experiment, nil
}

func (m *Manager) run(ctx context.Context, id string, profile Profile) {
	defer m.wg.Done()
	activation := time.NewTimer(time.Duration(profile.ActivationDelayMs) * time.Millisecond)
	defer activation.Stop()
	select {
	case <-ctx.Done():
		m.cleanupAndFinish(id, "completed", "stopped_before_activation", "")
		return
	case <-activation.C:
	}
	m.mu.Lock()
	if experiment := m.experiments[id]; experiment != nil {
		if err := m.recordLocked("applying", experiment); err != nil {
			m.mu.Unlock()
			m.cleanupAndFinish(id, "failed", "audit_failed", "write applying audit: "+err.Error())
			return
		}
	}
	m.mu.Unlock()

	applyCtx, cancelApply := context.WithTimeout(ctx, 15*time.Second)
	err := m.controller.Apply(applyCtx, profile)
	cancelApply()
	if err != nil {
		m.cleanupAndFinish(id, "failed", "apply_failed", err.Error())
		return
	}
	now := time.Now().UTC()
	expectedClear := now.Add(time.Duration(profile.DurationMs) * time.Millisecond)
	m.mu.Lock()
	if experiment := m.experiments[id]; experiment != nil {
		experiment.Phase = "active"
		experiment.ActiveAt = &now
		experiment.ExpectedClearAt = &expectedClear
		if err := m.recordLocked("active", experiment); err != nil {
			experiment.Phase = "failed"
			experiment.Error = "write active audit: " + err.Error()
			m.mu.Unlock()
			m.cleanupAndFinish(id, "failed", "audit_failed", experiment.Error)
			return
		}
	}
	m.mu.Unlock()

	duration := time.NewTimer(time.Duration(profile.DurationMs) * time.Millisecond)
	defer duration.Stop()
	reason := "duration_elapsed"
	select {
	case <-ctx.Done():
		reason = "stop_requested"
	case <-duration.C:
	}

	m.mu.Lock()
	if experiment := m.experiments[id]; experiment != nil {
		experiment.Phase = "clearing"
		if err := m.recordLocked("clearing", experiment); err != nil {
			experiment.Error = "write clearing audit: " + err.Error()
		}
	}
	m.mu.Unlock()
	m.cleanupAndFinish(id, "completed", reason, "")
}

func (m *Manager) cleanupAndFinish(id, finalPhase, reason, errorText string) {
	m.cleanupMu.Lock()
	defer m.cleanupMu.Unlock()
	m.mu.Lock()
	experiment := m.experiments[id]
	if experiment == nil || (experiment.CleanupVerified && experiment.Phase != "cleanup_failed") {
		m.mu.Unlock()
		return
	}
	if experiment.Phase != "cleanup_failed" {
		if errorText == "" {
			errorText = experiment.Error
		}
		experiment.finalError = errorText
	}
	m.mu.Unlock()
	clearCtx, cancelClear := context.WithTimeout(context.Background(), 10*time.Second)
	clearErr := m.controller.Clear(clearCtx)
	cancelClear()
	if clearErr != nil {
		if errorText != "" {
			errorText += "; "
		}
		errorText += "cleanup: " + clearErr.Error()
		m.finish(id, finalPhase, reason, errorText, false)
		return
	}
	m.finish(id, finalPhase, reason, errorText, true)
}

func (m *Manager) finish(id, finalPhase, reason, errorText string, cleanupVerified bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	experiment := m.experiments[id]
	if experiment == nil {
		return
	}
	now := time.Now().UTC()
	experiment.finalPhase = finalPhase
	experiment.CleanupVerified = cleanupVerified
	experiment.StopReason = reason
	if errorText != "" {
		experiment.Error = errorText
	}
	if cleanupVerified {
		experiment.Phase = finalPhase
		experiment.ClearedAt = &now
		experiment.Error = experiment.finalError
		if m.activeID == id {
			m.activeID = ""
			m.activeStop = nil
		}
	} else {
		experiment.Phase = "cleanup_failed"
		experiment.ClearedAt = nil
		m.activeID = id
		m.activeStop = nil
	}
	if err := m.recordLocked(experiment.Phase, experiment); err != nil && experiment.Error == "" {
		experiment.Error = "write terminal audit: " + err.Error()
	}
}

func (m *Manager) Stop(id string) (Experiment, error) {
	m.mu.Lock()
	experiment := m.experiments[id]
	if experiment == nil {
		m.mu.Unlock()
		return Experiment{}, ErrNotFound
	}
	if experiment.Phase == "cleanup_failed" {
		finalPhase := experiment.finalPhase
		reason := experiment.StopReason
		m.mu.Unlock()
		m.cleanupAndFinish(id, finalPhase, reason, "")
		return m.Get(id)
	}
	if m.activeID == id && m.activeStop != nil {
		m.activeStop()
	}
	result := *experiment
	m.mu.Unlock()
	return result, nil
}

func (m *Manager) Get(id string) (Experiment, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	experiment := m.experiments[id]
	if experiment == nil {
		return Experiment{}, ErrNotFound
	}
	return *experiment, nil
}

func (m *Manager) Status() *Experiment {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.activeID == "" {
		return nil
	}
	experiment := *m.experiments[m.activeID]
	return &experiment
}

func (m *Manager) Close() error {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return nil
	}
	m.closed = true
	if m.activeStop != nil {
		m.activeStop()
	}
	m.cancel()
	m.mu.Unlock()
	m.wg.Wait()
	m.cleanupMu.Lock()
	defer m.cleanupMu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := m.controller.Clear(ctx); err != nil {
		m.mu.Lock()
		id := m.activeID
		m.mu.Unlock()
		if id != "" {
			m.finish(id, "failed", "manager_close", "cleanup: "+err.Error(), false)
		}
		return err
	}
	m.mu.Lock()
	id := m.activeID
	m.mu.Unlock()
	if id != "" {
		experiment, getErr := m.Get(id)
		if getErr == nil && experiment.Phase == "cleanup_failed" {
			m.finish(id, experiment.finalPhase, experiment.StopReason, "", true)
		}
	}
	return nil
}

func (m *Manager) recordLocked(event string, experiment *Experiment) error {
	return m.auditor.Record(AuditEvent{
		GatewayVersion: GatewayVersion,
		Event:          event,
		RecordedAt:     time.Now().UTC(),
		Experiment:     *experiment,
	})
}

func randomID() (string, error) {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return hex.EncodeToString(raw), nil
}
