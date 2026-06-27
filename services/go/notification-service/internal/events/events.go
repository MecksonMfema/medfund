// Package events wires the Kafka consumer for InvoicePdfReady and the
// publisher for NotificationSent. Same patterns as file-service so
// future operators can read both services interchangeably.
package events

import (
	"context"
	"encoding/json"
	"log"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
)

// ── Consumer ─────────────────────────────────────────────────────────

type Subscriber struct {
	brokers string
	topic   string
	groupID string
}

func NewSubscriber(brokers, topic, groupID string) *Subscriber {
	return &Subscriber{brokers: brokers, topic: topic, groupID: groupID}
}

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
		Logger:         kafka.LoggerFunc(func(msg string, args ...interface{}) {}),
		ErrorLogger: kafka.LoggerFunc(func(msg string, args ...interface{}) {
			log.Printf("[notification][%s] %s", s.topic, msg)
		}),
	})
	defer r.Close()

	log.Printf("[notification] subscribed: topic=%s group=%s", s.topic, s.groupID)
	for {
		msg, err := r.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("[notification][%s] fetch error: %v — retrying in 3s", s.topic, err)
			select {
			case <-ctx.Done():
				return
			case <-time.After(3 * time.Second):
				continue
			}
		}
		handle(msg.Value)
		if err := r.CommitMessages(ctx, msg); err != nil {
			log.Printf("[notification][%s] commit error: %v", s.topic, err)
		}
	}
}

// ── Publisher: NotificationSent ──────────────────────────────────────

const TopicNotificationSent = "medfund.notifications.sent"

// NotificationSent is emitted after a successful (or failed) email
// dispatch — feeds audit + delivery dashboards.
type NotificationSent struct {
	Event         string `json:"event"`
	InvoiceID     string `json:"invoiceId"`
	InvoiceNumber string `json:"invoiceNumber"`
	TenantID      string `json:"tenantId"`
	Recipient     string `json:"recipient"`
	Channel       string `json:"channel"` // "EMAIL"
	Status        string `json:"status"`  // "SENT" or "FAILED"
	Error         string `json:"error,omitempty"`
}

type Publisher struct {
	writer *kafka.Writer
}

func NewPublisher(brokers string) *Publisher {
	return &Publisher{
		writer: &kafka.Writer{
			Addr:                   kafka.TCP(strings.Split(brokers, ",")...),
			Topic:                  TopicNotificationSent,
			Balancer:               &kafka.LeastBytes{},
			AllowAutoTopicCreation: true,
		},
	}
}

func (p *Publisher) Publish(ctx context.Context, n NotificationSent) {
	if n.Event == "" {
		n.Event = "NOTIFICATION_SENT"
	}
	body, err := json.Marshal(n)
	if err != nil {
		log.Printf("[notification] marshal NotificationSent: %v", err)
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	if err := p.writer.WriteMessages(ctx, kafka.Message{
		Key:   []byte(n.InvoiceID),
		Value: body,
	}); err != nil {
		log.Printf("[notification] publish NotificationSent failed: %v", err)
	}
}

func (p *Publisher) Close() { _ = p.writer.Close() }
