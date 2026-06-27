// Package retry runs an operation on a fixed exponential-ish backoff
// with a bounded number of concurrent retry chains in flight.
//
// Why not a stock library: every Go retry package wants to *own* the
// loop and block the caller. We want fire-and-forget — a Kafka
// consumer publishes the initial outcome immediately, hands the
// failed event to RunAsync, and keeps consuming. The retry chain
// lives in its own goroutine and reports its terminal outcome via a
// callback. The semaphore guards against an SMTP outage spawning
// thousands of waiting goroutines for a burst of failed dispatches.
//
// No persistence — if notification-service restarts mid-backoff the
// in-flight retries are lost. That's an explicit tradeoff: keeping
// retry state on disk doubles the moving parts (a delayed-message
// topic or a retry table) for a feature that already has an audit
// trail of failures and a "manual resend" UI on the roadmap.
package retry

import (
	"context"
	"errors"
	"log"
	"time"
)

// Scheduler runs operations through a fixed backoff sequence with a
// bounded number of concurrent retry chains.
type Scheduler struct {
	// Backoff is the delay before each attempt. len(Backoff) is the
	// max number of retries — i.e. {30s, 2m, 5m, 15m} means at most
	// 4 retry attempts before dead-lettering, totalling ~22 minutes.
	Backoff []time.Duration

	// concurrency cap — buffered semaphore.
	sem chan struct{}
}

// DefaultBackoff is the schedule applied when no override is given.
// Four retries spanning ~22 minutes; chosen so a transient SMTP blip
// resolves within the window without crowding the log with hundreds
// of attempts.
var DefaultBackoff = []time.Duration{
	30 * time.Second,
	2 * time.Minute,
	5 * time.Minute,
	15 * time.Minute,
}

// New builds a Scheduler. If concurrency <= 0 it defaults to 50;
// if backoff is nil it defaults to DefaultBackoff.
func New(backoff []time.Duration, concurrency int) *Scheduler {
	if concurrency <= 0 {
		concurrency = 50
	}
	if backoff == nil {
		backoff = DefaultBackoff
	}
	return &Scheduler{
		Backoff: backoff,
		sem:     make(chan struct{}, concurrency),
	}
}

// Outcome is the terminal report from RunAsync. Ok == true when fn
// eventually returned nil; Attempts is the retry number that produced
// the final result (so 1 means the first retry — the initial attempt
// before RunAsync is not counted here). Err is the last error seen
// when Ok is false.
type Outcome struct {
	Ok       bool
	Attempts int
	Err      error
}

// RunAsync queues a retry chain for fn. fn(ctx, attempt) is called
// after each successive backoff delay; attempt starts at 1 (the first
// *retry* — the caller's original invocation is not counted). When fn
// returns nil the chain stops and onFinal(Ok=true) fires; when fn
// returns non-nil for every attempt onFinal(Ok=false) fires with
// Attempts = len(Backoff).
//
// RunAsync blocks the caller only until a semaphore slot is available;
// the actual retry loop runs in a goroutine. Cancelling parentCtx
// aborts the chain and fires onFinal with the ctx error.
//
// key is a short identifier (e.g. invoice number) used in log lines —
// it's how an operator finds a specific retry chain in the logs
// without having to grep on the full event payload.
func (s *Scheduler) RunAsync(parentCtx context.Context, key string,
	fn func(ctx context.Context, attempt int) error,
	onFinal func(Outcome)) {

	s.sem <- struct{}{}
	go func() {
		defer func() { <-s.sem }()

		var lastErr error
		for attempt := 1; attempt <= len(s.Backoff); attempt++ {
			delay := s.Backoff[attempt-1]
			select {
			case <-time.After(delay):
			case <-parentCtx.Done():
				log.Printf("[retry] %s — cancelled before attempt %d: %v",
					key, attempt, parentCtx.Err())
				onFinal(Outcome{Attempts: attempt - 1, Err: parentCtx.Err()})
				return
			}

			err := fn(parentCtx, attempt)
			if err == nil {
				log.Printf("[retry] %s — succeeded on attempt %d", key, attempt)
				onFinal(Outcome{Ok: true, Attempts: attempt})
				return
			}
			lastErr = err
			log.Printf("[retry] %s — attempt %d failed: %v", key, attempt, err)
		}

		if lastErr == nil {
			lastErr = errors.New("max retry attempts exhausted")
		}
		log.Printf("[retry] %s — dead-letter after %d attempts: %v",
			key, len(s.Backoff), lastErr)
		onFinal(Outcome{Attempts: len(s.Backoff), Err: lastErr})
	}()
}
