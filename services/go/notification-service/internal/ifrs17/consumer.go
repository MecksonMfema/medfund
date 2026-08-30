package ifrs17

import (
	"context"
	"encoding/json"
	"log"

	"github.com/medfund/notification-service/internal/events"
)

// Topic is the Kafka topic the Java-side publishers write to
// (KafkaIfrs17MaterialEventPublisher in user-service and
// Ifrs17MaterialEventPublisher in finance-service both target this).
const Topic = topic

// Run subscribes to the material-event topic and hands each decoded
// event to the dispatcher. The consumer commits on every payload,
// including drop paths, because a malformed event is not a
// transient failure — retrying would just re-drop it and stall the
// partition. Genuine dispatch failures are logged; per-recipient
// retry lives inside the Dispatcher.
func Run(ctx context.Context, brokers, groupID string, dispatcher *Dispatcher) {
	sub := events.NewSubscriber(brokers, Topic, groupID+"-ifrs17-material")
	sub.Run(ctx, func(payload []byte) {
		var e Event
		if err := json.Unmarshal(payload, &e); err != nil {
			log.Printf("[ifrs17] drop malformed material event: %v", err)
			return
		}
		res := dispatcher.Dispatch(ctx, e)
		if res.Err != nil {
			log.Printf("[ifrs17] dispatch failed tenant=%s type=%s: %v",
				e.TenantID, e.EventType, res.Err)
			return
		}
		log.Printf("[ifrs17] processed tenant=%s type=%s recipients=%d delivered=%d throttled=%d "+
			"failedEmails=%d failedHooks=%d",
			e.TenantID, e.EventType, res.Attempts, res.Delivered, res.Throttled,
			res.FailedEmails, res.FailedHooks)
	})
}
