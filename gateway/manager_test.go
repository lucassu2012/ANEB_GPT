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

type concurrentCleanupController struct {
	mu           sync.Mutex
	clearCount   int
	retryStarted chan struct{}
	releaseRetry chan struct{}
	allowFurther bool
}

func (c *concurrentCleanupController) Apply(context.Context, Profile) error { return nil }

func (c *concurrentCleanupController) Clear(context.Context) error {
	c.mu.Lock()
	c.clearCount++
	call := c.clearCount
	allowFurther := c.allowFurther
	c.mu.Unlock()
	switch call {
	case 1:
		return nil // NewManager startup cleanup.
	case 2:
		return errors.New("first cleanup failed")
	case 3:
		close(c.retryStarted)
		<-c.releaseRetry
		return nil
	default:
		if allowFurther {
			return nil
		}
		return errors.New("late concurrent cleanup must never run")
	}
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
	if completed.StopReason != "duration_elapsed" || completed.ActiveAt == nil || completed.ClearedAt == nil || !completed.CleanupVerified {
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
	completed := waitForPhase(t, manager, experiment.ExperimentID, "completed", time.Second)
	if !completed.CleanupVerified || completed.ClearedAt == nil {
		t.Fatalf("stop cleanup was not verified: %+v", completed)
	}
	controller.mu.Lock()
	defer controller.mu.Unlock()
	if controller.applyCount != 0 {
		t.Fatalf("apply count=%d", controller.applyCount)
	}
}

func TestManagerLatchesCleanupFailureUntilVerifiedRetry(t *testing.T) {
	controller := &fakeController{}
	profile := validProfile()
	manager, err := NewManager(context.Background(), map[string]Profile{profile.Ref(): profile}, controller, &memoryAuditor{})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	controller.mu.Lock()
	controller.clearErr = errors.New("qdisc remains")
	controller.mu.Unlock()

	experiment, err := manager.Start("run-latched", profile.Ref())
	if err != nil {
		t.Fatal(err)
	}
	failed := waitForPhase(t, manager, experiment.ExperimentID, "cleanup_failed", time.Second)
	if failed.ClearedAt != nil || failed.CleanupVerified || manager.Status() == nil {
		t.Fatalf("cleanup failure was not latched: %+v", failed)
	}
	if _, err := manager.Start("run-blocked", profile.Ref()); !errors.Is(err, ErrCleanupLatched) {
		t.Fatalf("start while cleanup failed error=%v", err)
	}

	controller.mu.Lock()
	controller.clearErr = nil
	controller.mu.Unlock()
	retried, err := manager.Stop(experiment.ExperimentID)
	if err != nil {
		t.Fatal(err)
	}
	if retried.Phase != "completed" || !retried.CleanupVerified || retried.ClearedAt == nil {
		t.Fatalf("verified retry did not release latch: %+v", retried)
	}
	if manager.Status() != nil {
		t.Fatal("successful cleanup retry left gateway latched")
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

func TestManagerStartIsIdempotentForSameRunAndProfileIncludingTerminal(t *testing.T) {
	controller := &fakeController{}
	profile := validProfile()
	manager, err := NewManager(context.Background(), map[string]Profile{profile.Ref(): profile}, controller, &memoryAuditor{})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	first, err := manager.Start("run-idempotent", profile.Ref())
	if err != nil {
		t.Fatal(err)
	}

	const callers = 16
	ids := make(chan string, callers)
	errs := make(chan error, callers)
	var wg sync.WaitGroup
	for range callers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			experiment, startErr := manager.Start("run-idempotent", profile.Ref())
			if startErr != nil {
				errs <- startErr
				return
			}
			ids <- experiment.ExperimentID
		}()
	}
	wg.Wait()
	close(ids)
	close(errs)
	for startErr := range errs {
		t.Fatalf("idempotent start failed: %v", startErr)
	}
	for id := range ids {
		if id != first.ExperimentID {
			t.Fatalf("idempotent start returned id=%s want=%s", id, first.ExperimentID)
		}
	}
	completed := waitForPhase(t, manager, first.ExperimentID, "completed", time.Second)
	replayed, err := manager.Start("run-idempotent", profile.Ref())
	if err != nil {
		t.Fatal(err)
	}
	if replayed.ExperimentID != completed.ExperimentID || replayed.Phase != "completed" {
		t.Fatalf("terminal replay=%+v", replayed)
	}
	controller.mu.Lock()
	defer controller.mu.Unlock()
	if controller.applyCount != 1 {
		t.Fatalf("idempotent run applied %d times", controller.applyCount)
	}
}

func TestManagerRejectsSameRunWithDifferentProfile(t *testing.T) {
	controller := &fakeController{}
	firstProfile := validProfile()
	secondProfile := validProfile()
	secondProfile.ProfileID = "second_profile"
	manager, err := NewManager(context.Background(), map[string]Profile{
		firstProfile.Ref(): firstProfile, secondProfile.Ref(): secondProfile,
	}, controller, &memoryAuditor{})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	if _, err := manager.Start("run-profile-bound", firstProfile.Ref()); err != nil {
		t.Fatal(err)
	}
	if _, err := manager.Start("run-profile-bound", secondProfile.Ref()); !errors.Is(err, ErrRunConflict) {
		t.Fatalf("profile conflict error=%v", err)
	}
}

func TestManagerSerializesCleanupAndIgnoresLateRetry(t *testing.T) {
	controller := &concurrentCleanupController{
		retryStarted: make(chan struct{}),
		releaseRetry: make(chan struct{}),
	}
	profile := validProfile()
	manager, err := NewManager(context.Background(), map[string]Profile{profile.Ref(): profile}, controller, &memoryAuditor{})
	if err != nil {
		t.Fatal(err)
	}
	experiment, err := manager.Start("run-cleanup-single-flight", profile.Ref())
	if err != nil {
		t.Fatal(err)
	}
	waitForPhase(t, manager, experiment.ExperimentID, "cleanup_failed", time.Second)

	results := make(chan Experiment, 2)
	errorsFound := make(chan error, 2)
	go func() {
		result, stopErr := manager.Stop(experiment.ExperimentID)
		results <- result
		errorsFound <- stopErr
	}()
	<-controller.retryStarted
	go func() {
		result, stopErr := manager.Stop(experiment.ExperimentID)
		results <- result
		errorsFound <- stopErr
	}()
	close(controller.releaseRetry)
	for range 2 {
		if stopErr := <-errorsFound; stopErr != nil {
			t.Fatal(stopErr)
		}
		result := <-results
		if result.Phase != "completed" || !result.CleanupVerified {
			t.Fatalf("cleanup result=%+v", result)
		}
	}
	controller.mu.Lock()
	if controller.clearCount != 3 {
		t.Fatalf("cleanup calls=%d want=3", controller.clearCount)
	}
	controller.allowFurther = true
	controller.mu.Unlock()
	if manager.Status() != nil {
		t.Fatal("verified cleanup was overwritten by a late retry")
	}
	if err := manager.Close(); err != nil {
		t.Fatal(err)
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
