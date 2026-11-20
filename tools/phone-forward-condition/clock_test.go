package condition

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestSystemClockCancellationDoesNotWaitForDistantSchedule(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	start := time.Now()
	err := (SystemClock{}).WaitUntil(ctx, start.Add(time.Hour))
	if !errors.Is(err, context.Canceled) || time.Since(start) > 100*time.Millisecond {
		t.Fatalf("cancelled wait did not terminate: %v", err)
	}
	if err := (SystemClock{}).WaitUntil(context.Background(), start.Add(-time.Second)); err != nil {
		t.Fatal(err)
	}
}
