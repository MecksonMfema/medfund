package events

import (
	"context"
	"log"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
)

const TopicPaymentRunExecuted = "medfund.payments.run.executed"

// Subscriber wraps a single-topic kafka-go reader. Mirrors the
// file-service consumer pattern: FirstOffset on initial run, committed
// offsets thereafter; commit only after the handler returns so a crash
// mid-handle replays the message.
type Subscriber struct {
	brokers string
	topic   string
	groupID string
}

func NewSubscriber(brokers, groupID string) *Subscriber {
	return &Subscriber{
		brokers: brokers,
		topic:   TopicPaymentRunExecuted,
		groupID: groupID,
	}
}

// Run blocks on the given context, dispatching each fetched message's
// value to handle. Cancel ctx to stop.
func (s *Subscriber) Run(ctx context.Context, handle func(payload []byte)) {
	r := kafka.NewReader(kafka.ReaderConfig{
		Brokers:        strings.Split(s.brokers, ","),
		Topic:          s.topic,
		GroupID:        s.groupID,
		MinBytes:       1,
		MaxBytes:       10 << 20,
		MaxWait:        500 * time.Millisecond,
		StartOffset:    kafka.FirstOffset,
		CommitInterval: time.Second,
		Logger:         kafka.LoggerFunc(func(string, ...interface{}) {}),
		ErrorLogger: kafka.LoggerFunc(func(msg string, args ...interface{}) {
			log.Printf("[payment-gateway][%s] %s", s.topic, msg)
		}),
	})
	defer r.Close()

	log.Printf("[payment-gateway] subscribed: topic=%s group=%s", s.topic, s.groupID)
	for {
		msg, err := r.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("[payment-gateway][%s] fetch error: %v — retrying in 3s", s.topic, err)
			select {
			case <-ctx.Done():
				return
			case <-time.After(3 * time.Second):
				continue
			}
		}
		handle(msg.Value)
		if err := r.CommitMessages(ctx, msg); err != nil {
			log.Printf("[payment-gateway][%s] commit error: %v", s.topic, err)
		}
	}
}
