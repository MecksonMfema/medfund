package report

import (
	"context"
	"encoding/json"
	"log"

	"github.com/medfund/notification-service/internal/events"
)

// Run wires two Kafka subscribers — one per topic — into a single
// dispatcher. The consumer commits offsets on every message; malformed
// payloads are dropped (retrying a shape error just re-drops it and
// stalls the partition). Per-recipient failures inside DispatchDelivery
// / DispatchFailure are already logged there.
//
// Deploy-order per F-S9: this consumer must be live BEFORE
// finance-service's ScheduledReportProbe begins publishing, otherwise
// early events land against an empty consumer group.
func Run(ctx context.Context, brokers, groupID string, dispatcher *Dispatcher) {
	go runDelivery(ctx, brokers, groupID, dispatcher)
	go runFailure(ctx, brokers, groupID, dispatcher)
}

func runDelivery(ctx context.Context, brokers, groupID string, dispatcher *Dispatcher) {
	sub := events.NewSubscriber(brokers, DeliveryTopic, groupID+"-report-delivery")
	sub.Run(ctx, func(payload []byte) {
		var e DeliveryEvent
		if err := json.Unmarshal(payload, &e); err != nil {
			log.Printf("[report] drop malformed delivery event: %v", err)
			return
		}
		results := dispatcher.DispatchDelivery(ctx, e)
		summarise("delivery", e.TenantID, e.ScheduleID, e.ReportKey, results)
	})
}

func runFailure(ctx context.Context, brokers, groupID string, dispatcher *Dispatcher) {
	sub := events.NewSubscriber(brokers, DeliveryFailedTopic, groupID+"-report-failure")
	sub.Run(ctx, func(payload []byte) {
		var e DeliveryFailedEvent
		if err := json.Unmarshal(payload, &e); err != nil {
			log.Printf("[report] drop malformed failure event: %v", err)
			return
		}
		results := dispatcher.DispatchFailure(ctx, e)
		summarise("failure", e.TenantID, e.ScheduleID, e.ReportKey, results)
	})
}

func summarise(kind, tenantID, scheduleID, reportKey string, results []Result) {
	var ok, failed int
	for _, r := range results {
		if r.Err != nil {
			failed++
		} else if r.Ok {
			ok++
		}
	}
	log.Printf("[report] %s tenant=%s schedule=%s key=%s delivered=%d failed=%d",
		kind, tenantID, scheduleID, reportKey, ok, failed)
}
