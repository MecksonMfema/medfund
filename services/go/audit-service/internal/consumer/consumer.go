package consumer

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/medfund/audit-service/internal/audit"
	"github.com/segmentio/kafka-go"
)

const (
	topicAuditEvents    = "medfund.audit.events"
	topicSecurityEvents = "medfund.security.events"
	groupID             = "audit-service"
)

// AuditConsumer reads audit and security events from Kafka and appends them to the store.
type AuditConsumer struct {
	brokers string
	store   *audit.Store
}

func New(brokers string, store *audit.Store) *AuditConsumer {
	return &AuditConsumer{brokers: brokers, store: store}
}

// Start launches Kafka consumers for both audit and security event topics.
// It returns immediately; consumption runs in background goroutines.
// Cancel ctx to stop.
func (c *AuditConsumer) Start(ctx context.Context) {
	go c.consume(ctx, topicAuditEvents, c.handleAuditEvent)
	go c.consume(ctx, topicSecurityEvents, c.handleSecurityEvent)
	log.Printf("[audit-consumer] Listening on topics: %s, %s", topicAuditEvents, topicSecurityEvents)
}

func (c *AuditConsumer) consume(ctx context.Context, topic string, handle func([]byte)) {
	brokerList := strings.Split(c.brokers, ",")
	r := kafka.NewReader(kafka.ReaderConfig{
		Brokers:        brokerList,
		Topic:          topic,
		GroupID:        groupID,
		MinBytes:       1,
		MaxBytes:       10 << 20, // 10 MB
		MaxWait:        500 * time.Millisecond,
		// FirstOffset: on first run (or after consumer-group reset) replay all
		// retained messages so the in-memory store is fully populated on startup.
		// For subsequent restarts the committed group offset takes precedence.
		StartOffset:    kafka.FirstOffset,
		CommitInterval: time.Second,
		Logger:         kafka.LoggerFunc(func(msg string, args ...interface{}) {}), // silence info logs
		ErrorLogger: kafka.LoggerFunc(func(msg string, args ...interface{}) {
			log.Printf("[audit-consumer][%s] %s", topic, fmt.Sprintf(msg, args...))
		}),
	})
	defer r.Close()

	for {
		msg, err := r.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return // graceful shutdown
			}
			log.Printf("[audit-consumer][%s] fetch error: %v — retrying in 3s", topic, err)
			select {
			case <-ctx.Done():
				return
			case <-time.After(3 * time.Second):
				continue
			}
		}

		handle(msg.Value)

		if err := r.CommitMessages(ctx, msg); err != nil {
			log.Printf("[audit-consumer][%s] commit error: %v", topic, err)
		}
	}
}

func (c *AuditConsumer) handleAuditEvent(data []byte) {
	var event audit.Event
	if err := json.Unmarshal(data, &event); err != nil {
		log.Printf("[audit-consumer] failed to parse audit event: %v", err)
		return
	}
	if event.Timestamp.IsZero() {
		event.Timestamp = time.Now()
	}
	c.store.Append(event)
	log.Printf("[audit-consumer] stored: %s %s/%s by %s",
		event.Action, event.EntityType, event.EntityID, event.ActorID)
}

func (c *AuditConsumer) handleSecurityEvent(data []byte) {
	var event audit.SecurityEvent
	if err := json.Unmarshal(data, &event); err != nil {
		log.Printf("[audit-consumer] failed to parse security event: %v", err)
		return
	}
	if event.Timestamp.IsZero() {
		event.Timestamp = time.Now()
	}
	c.store.AppendSecurity(event)
	log.Printf("[audit-consumer] security event: %s user=%s", event.EventType, event.UserID)
}
