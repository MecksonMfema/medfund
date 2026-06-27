package retry

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Tiny backoffs so the suite stays fast — 1ms each is plenty to
// exercise the timing path without slowing down go test.
var tinyBackoff = []time.Duration{1 * time.Millisecond, 1 * time.Millisecond, 1 * time.Millisecond}

func waitFor(t *testing.T, ch <-chan Outcome) Outcome {
	t.Helper()
	select {
	case o := <-ch:
		return o
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for retry outcome")
		return Outcome{}
	}
}

func TestRunAsync_succeedsOnFirstAttempt(t *testing.T) {
	s := New(tinyBackoff, 4)
	done := make(chan Outcome, 1)
	s.RunAsync(context.Background(), "k1",
		func(_ context.Context, _ int) error { return nil },
		func(o Outcome) { done <- o })
	o := waitFor(t, done)
	if !o.Ok || o.Attempts != 1 {
		t.Errorf("want Ok=true Attempts=1, got %+v", o)
	}
}

func TestRunAsync_succeedsAfterRetries(t *testing.T) {
	s := New(tinyBackoff, 4)
	var n int32
	done := make(chan Outcome, 1)
	s.RunAsync(context.Background(), "k2",
		func(_ context.Context, _ int) error {
			if atomic.AddInt32(&n, 1) < 3 {
				return errors.New("flaky")
			}
			return nil
		},
		func(o Outcome) { done <- o })
	o := waitFor(t, done)
	if !o.Ok || o.Attempts != 3 {
		t.Errorf("want Ok=true Attempts=3, got %+v", o)
	}
}

func TestRunAsync_deadLettersAfterExhaustion(t *testing.T) {
	s := New(tinyBackoff, 4)
	wantErr := errors.New("nope")
	done := make(chan Outcome, 1)
	s.RunAsync(context.Background(), "k3",
		func(_ context.Context, _ int) error { return wantErr },
		func(o Outcome) { done <- o })
	o := waitFor(t, done)
	if o.Ok {
		t.Errorf("expected dead-letter, got success: %+v", o)
	}
	if o.Attempts != len(tinyBackoff) {
		t.Errorf("Attempts = %d, want %d (len(Backoff))", o.Attempts, len(tinyBackoff))
	}
	if !errors.Is(o.Err, wantErr) {
		t.Errorf("final Err should propagate the last fn error, got %v", o.Err)
	}
}

func TestRunAsync_respectsContextCancellation(t *testing.T) {
	s := New([]time.Duration{200 * time.Millisecond, 200 * time.Millisecond}, 4)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan Outcome, 1)
	s.RunAsync(ctx, "k4",
		func(_ context.Context, _ int) error { return errors.New("x") },
		func(o Outcome) { done <- o })

	cancel() // pull the rug before the first backoff elapses
	o := waitFor(t, done)
	if o.Ok {
		t.Errorf("expected ctx-cancel, got success: %+v", o)
	}
	if !errors.Is(o.Err, context.Canceled) {
		t.Errorf("final Err should be ctx.Err, got %v", o.Err)
	}
}

func TestRunAsync_concurrencyCapBoundsInFlightChains(t *testing.T) {
	// Concurrency=2 with a sleepy fn — at most 2 chains should be
	// running at once. We spawn 5 chains and assert no more than 2 are
	// concurrently inside fn at any point.
	s := New([]time.Duration{1 * time.Millisecond}, 2)

	var inFlight, peak int32
	var mu sync.Mutex
	track := func() {
		mu.Lock()
		defer mu.Unlock()
		if inFlight > peak {
			peak = inFlight
		}
	}

	done := make(chan Outcome, 5)
	for i := 0; i < 5; i++ {
		s.RunAsync(context.Background(), "kN",
			func(_ context.Context, _ int) error {
				atomic.AddInt32(&inFlight, 1)
				track()
				time.Sleep(40 * time.Millisecond) // hold the slot
				atomic.AddInt32(&inFlight, -1)
				return nil
			},
			func(o Outcome) { done <- o })
	}
	for i := 0; i < 5; i++ {
		waitFor(t, done)
	}
	if peak > 2 {
		t.Errorf("concurrent retries exceeded cap: peak=%d, want <=2", peak)
	}
}
