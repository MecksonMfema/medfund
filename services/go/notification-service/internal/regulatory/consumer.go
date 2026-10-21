package regulatory

import (
	"context"
	"encoding/json"
	"log"

	"github.com/medfund/notification-service/internal/events"
)

// Run subscribes to the regulator due-date topic and hands each decoded
// event to the dispatcher. The consumer commits on every payload —
// malformed events are dropped (retrying a shape error just re-drops it
// and stalls the partition) and dispatch failures are logged.
// Per-recipient failures inside Dispatch are already logged there.
func Run(ctx context.Context, brokers, groupID string, dispatcher *Dispatcher) {
	sub := events.NewSubscriber(brokers, Topic, groupID+"-regulatory-due-date")
	sub.Run(ctx, func(payload []byte) {
		var e Event
		if err := json.Unmarshal(payload, &e); err != nil {
			log.Printf("[reg-due-date] drop malformed event: %v", err)
			return
		}
		res := dispatcher.Dispatch(ctx, e)
		if res.Err != nil {
			log.Printf("[reg-due-date] dispatch failed tenant=%s key=%s tier=%s: %v",
				e.TenantID, e.ReportKey, e.EventTier, res.Err)
			return
		}
		log.Printf("[reg-due-date] processed tenant=%s key=%s tier=%s attempts=%d delivered=%d skipped=%d failed=%d",
			e.TenantID, e.ReportKey, e.EventTier, res.Attempts, res.Delivered,
			res.Skipped, res.FailedEmails)
	})
}
