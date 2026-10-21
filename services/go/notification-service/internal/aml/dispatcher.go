package aml

import (
	"context"
	"log"
)

// Result summarises one Dispatch call for logging + tests.
// Ok is always true unless the hook itself returns an error — the
// stub-log path in Phase 24 can never fail. HookInvoked flips when
// a non-nil Hook is registered and gets called.
type Result struct {
	Ok          bool
	HookInvoked bool
	HookErr     error
}

// Hook is the pluggable slot for the future fraud-detector AI
// integration. It receives every transition; a real implementation
// might call ai-service on RAISE, ingest the score, and emit a
// downstream event. Returning an error surfaces on Result.HookErr
// and is logged; it never blocks the dispatcher from consuming the
// next message.
type Hook func(ctx context.Context, event Event) error

// Dispatcher is the AML alert consumer's per-event handler. Fields are
// exported so the wiring in cmd/main.go can construct the dispatcher
// with the hook slot pre-filled — leave Hook nil for the stub
// (Phase 24) log-only behaviour.
type Dispatcher struct {
	Hook Hook
}

// NewDispatcher returns a dispatcher with the given (optional) hook.
// A nil hook means the dispatcher runs in stub mode — every event is
// decoded and logged, no side effect.
func NewDispatcher(hook Hook) *Dispatcher {
	return &Dispatcher{Hook: hook}
}

// Dispatch is the end-to-end pipeline for one suspicious-transaction
// event. In Phase 24 it just logs the transition + calls the optional
// hook. Never returns an error — a hook failure is captured on Result
// and logged so the consumer commits and moves on (a stuck partition
// on a badly-configured hook would silence every subsequent alert).
func (d *Dispatcher) Dispatch(ctx context.Context, event Event) Result {
	if d == nil {
		log.Printf("[aml] dispatcher not configured, dropping alertId=%s transition=%s",
			event.AlertID, event.Transition)
		return Result{Ok: true}
	}
	if event.AlertID == "" || event.Transition == "" || event.TenantID == "" {
		log.Printf("[aml] drop malformed event: tenantId=%q alertId=%q transition=%q",
			event.TenantID, event.AlertID, event.Transition)
		return Result{Ok: true}
	}
	if event.SchemaVersion == 0 {
		log.Printf("[aml] event without schemaVersion tenantId=%s alertId=%s — accepting for forward compat",
			event.TenantID, event.AlertID)
	}

	log.Printf("[aml] transition=%s alertId=%s tenant=%s txnRef=%s type=%s status=%s (was %s) actor=%s",
		event.Transition, event.AlertID, event.TenantID,
		event.TransactionRef, event.TransactionType, event.NewStatus,
		derefStatus(event.PriorStatus), event.ActorEmail)

	res := Result{Ok: true}
	if d.Hook != nil {
		res.HookInvoked = true
		if err := d.Hook(ctx, event); err != nil {
			log.Printf("[aml] hook error on alertId=%s transition=%s: %v",
				event.AlertID, event.Transition, err)
			res.HookErr = err
		}
	}
	return res
}

func derefStatus(s *string) string {
	if s == nil {
		return "(none)"
	}
	return *s
}
