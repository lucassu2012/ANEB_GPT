package condition

import (
	"context"
	"time"
)

type SystemClock struct{}

func (SystemClock) Now() time.Time { return time.Now() }
func (SystemClock) WaitUntil(ctx context.Context, due time.Time) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	wait := time.Until(due)
	if wait <= 0 {
		return nil
	}
	timer := time.NewTimer(wait)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
