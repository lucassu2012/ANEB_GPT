package gateway

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

type fakeController struct {
	mu         sync.Mutex
	applyCount int
	clearCount int
	applyErr   error
	clearErr   error
}

func (f *fakeController) Apply(context.Context, Profile) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.applyCount++
	return f.applyErr
}

func (f *fakeController) Clear(context.Context) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.clearCount++
	return f.clearErr
}

type memoryAuditor struct {
	mu     sync.Mutex
	events []AuditEvent
	err    error
}

func (a *memoryAuditor) Record(event AuditEvent) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.err != nil {
		return a.err
	}
	a.events = append(a.events, event)
	return nil
}

func TestManagerRunsOneExperimentAndAutomaticallyCleans(t *testing.T) {
	controller := &fakeController{}
	auditor := &memoryAuditor{}
	profile := validProfile()
	manager, err := NewManager(context.Background(), map[string]Profile{profile.Ref(): profile}, controller, auditor)
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()

	experiment, err := manager.Start("run-1", profile.Ref())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Start("run-2", profile.Ref()); !errors.Is(err, ErrExperimentActive) {
		t.Fatalf("second start error=%v", err)
	}
	waitForPhase(t, manager, experiment.ExperimentID, "active", time.Second)
	completed := waitForPhase(t, manager, experiment.ExperimentID, "completed", time.Second)
	if completed.StopReason != "duration_elapsed" || completed.ActiveAt == nil || completed.ClearedAt == nil {
		t.Fatalf("unexpected terminal experiment: %+v", completed)
	}
	controller.mu.Lock()
	if controller.applyCount != 1 || controller.clearCount < 2 {
		t.Fatalf("apply=%d clear=%d", controller.applyCount, controller.clearCount)
	}
	controller.mu.Unlock()
	auditor.mu.Lock()
	if len(auditor.events) < 4 || auditor.events[0].Event != "scheduled" || auditor.events[len(auditor.events)-1].Event != "completed" {
		t.Fatalf("audit events=%+v", auditor.events)
	}
	auditor.mu.Unlock()
}

func TestManagerStopBeforeActivationDoesNotApply(t *testing.T) {
	controller := &fakeController{}
	profile := validProfile()
	profile.ActivationDelayMs = 500
	manager, err := NewManager(context.Background(), map[string]Profile{profile.Ref(): profile}, controller, &memoryAuditor{})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	experiment, err := manager.Start("run-stop", profile.Ref())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Stop(experiment.ExperimentID); err != nil {
		t.Fatal(err)
	}
	waitForPhase(t, manager, experiment.ExperimentID, "cancelled", time.Second)
	controller.mu.Lock()
	defer controller.mu.Unlock()
	if controller.applyCount != 0 {
		t.Fatalf("apply count=%d", controller.applyCount)
	}
}

func TestManagerRefusesExperimentWhenScheduledAuditCannotBeWritten(t *testing.T) {
	controller := &fakeController{}
	profile := validProfile()
	manager, err := NewManager(context.Background(), map[string]Profile{profile.Ref(): profile}, controller, &memoryAuditor{err: errors.New("disk full")})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	if _, err := manager.Start("run-audit", profile.Ref()); err == nil {
		t.Fatal("experiment started without an audit record")
	}
	if manager.Status() != nil {
		t.Fatal("failed start remained active")
	}
}

func waitForPhase(t *testing.T, manager *Manager, id, phase string, timeout time.Duration) Experiment {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		experiment, err := manager.Get(id)
		if err != nil {
			t.Fatal(err)
		}
		if experiment.Phase == phase {
			return experiment
		}
		time.Sleep(10 * time.Millisecond)
	}
	experiment, _ := manager.Get(id)
	t.Fatalf("phase=%s, want=%s", experiment.Phase, phase)
	return Experiment{}
}
