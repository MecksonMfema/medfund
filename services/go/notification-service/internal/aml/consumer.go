package aml

import (
	"context"
	"encoding/json"
	"log"

	"github.com/medfund/notification-service/internal/events"
)

// Run subscribes to the AML suspicious-transaction topic and hands each
// decoded event to the dispatcher. The consumer commits on every payload —
// malformed events are dropped (retrying a shape error just re-drops it
// and stalls the partition); hook failures are captured on Result and
// logged, never causing a re-fetch loop.
//
// Deploy this consumer BEFORE finance-service's producer starts
// publishing (F-REG7 invariant) — otherwise the first RAISE events land
// against an empty consumer group and are only picked up when this
// dispatcher rolls, arriving late.
func Run(ctx context.Context, brokers, groupID string, dispatcher *Dispatcher) {
	sub := events.NewSubscriber(brokers, Topic, groupID+"-aml-suspicious-transaction")
	sub.Run(ctx, func(payload []byte) {
		var e Event
		if err := json.Unmarshal(payload, &e); err != nil {
			log.Printf("[aml] drop malformed event: %v", err)
			return
		}
		res := dispatcher.Dispatch(ctx, e)
		if res.HookErr != nil {
			// Hook error is already logged inside Dispatch; this second
			// line surfaces the counter-level "hook was invoked and
			// failed" fact so grep-based ops can distinguish it from
			// the stub log-only path.
			log.Printf("[aml] hook failed alertId=%s transition=%s: %v",
				e.AlertID, e.Transition, res.HookErr)
			return
		}
		if res.HookInvoked {
			log.Printf("[aml] processed alertId=%s transition=%s hook=ok",
				e.AlertID, e.Transition)
		}
	})
}
