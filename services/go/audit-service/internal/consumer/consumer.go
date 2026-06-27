package consumer

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/medfund/audit-service/internal/audit"
	"github.com/segmentio/kafka-go"
)

const (
	topicAuditEvents        = "medfund.audit.events"
	topicSecurityEvents     = "medfund.security.events"
	topicNotificationsSent  = "medfund.notifications.sent"
	groupID                 = "audit-service"
)

// eventStore is the minimum surface of audit.Store the consumer needs.
// Introducing this seam lets the consumer be unit-tested without spinning a
// real Postgres pool — *audit.Store satisfies it as-is, so the public
// constructor signature stays unchanged.
type eventStore interface {
	Append(audit.Event)
	AppendSecurity(audit.SecurityEvent)
}

// AuditConsumer reads audit and security events from Kafka and appends them to the store.
type AuditConsumer struct {
	brokers string
	store   eventStore
}

func New(brokers string, store *audit.Store) *AuditConsumer {
	return &AuditConsumer{brokers: brokers, store: store}
}

// Start launches Kafka consumers for the three topics audit ingests.
// It returns immediately; consumption runs in background goroutines.
// Cancel ctx to stop.
func (c *AuditConsumer) Start(ctx context.Context) {
	go c.consume(ctx, topicAuditEvents, c.handleAuditEvent)
	go c.consume(ctx, topicSecurityEvents, c.handleSecurityEvent)
	go c.consume(ctx, topicNotificationsSent, c.handleNotificationSent)
	log.Printf("[audit-consumer] Listening on topics: %s, %s, %s",
		topicAuditEvents, topicSecurityEvents, topicNotificationsSent)
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

// notificationSent is the projection of the NotificationSent payload
// emitted by notification-service. Field names match the wire shape
// exactly so the unmarshal is one step.
type notificationSent struct {
	Event         string `json:"event"`
	InvoiceID     string `json:"invoiceId"`
	InvoiceNumber string `json:"invoiceNumber"`
	TenantID      string `json:"tenantId"`
	Recipient     string `json:"recipient"`
	Channel       string `json:"channel"`
	Status        string `json:"status"` // SENT or FAILED
	Error         string `json:"error,omitempty"`
}

// handleNotificationSent persists every email-dispatch outcome as a
// row in audit_events so a support operator can see per-invoice
// delivery status in /tenant/admin/audit alongside the CREATE row that
// minted the invoice.
//
// Mapping decisions (see .claude/coding-standards.md for the rules):
//   - EntityType = "Notification"  — distinct from "Invoice" since one
//     invoice can have many delivery attempts.
//   - EntityID   = invoiceId       — joins back to the Invoice row.
//   - EntityName = invoiceNumber   — friendly text per
//                                     [[feedback_audit_entity_name]].
//   - Action     = NOTIFICATION_SENT | NOTIFICATION_FAILED — distinct
//                  actions so the audit UI can colour them differently
//                  without parsing NewValue.
//   - ActorID    = "system"        — there's no human in the loop here;
//   - ActorEmail = "notifications@medfund.system" — never null per
//                                     [[feedback_audit_actor_email]].
//   - NewValue   = {recipient, channel, error?} — enough context for an
//                  operator to triage a failure without joining anywhere.
func (c *AuditConsumer) handleNotificationSent(data []byte) {
	var n notificationSent
	if err := json.Unmarshal(data, &n); err != nil {
		log.Printf("[audit-consumer] failed to parse notification event: %v", err)
		return
	}
	if n.InvoiceID == "" || n.TenantID == "" {
		log.Printf("[audit-consumer] dropping notification missing required ids: %+v", n)
		return
	}

	action := "NOTIFICATION_SENT"
	if n.Status == "FAILED" {
		action = "NOTIFICATION_FAILED"
	}

	newValue := map[string]interface{}{
		"recipient": n.Recipient,
		"channel":   n.Channel,
	}
	if n.Error != "" {
		newValue["error"] = n.Error
	}

	entityName := n.InvoiceNumber
	if entityName == "" {
		entityName = n.InvoiceID
	}

	c.store.Append(audit.Event{
		ID:         uuid.New().String(),
		TenantID:   n.TenantID,
		EntityType: "Notification",
		EntityID:   n.InvoiceID,
		EntityName: entityName,
		Action:     action,
		ActorID:    "system",
		ActorEmail: "notifications@medfund.system",
		NewValue:   newValue,
		Timestamp:  time.Now(),
	})
	log.Printf("[audit-consumer] notification: %s invoice=%s recipient=%s",
		action, n.InvoiceNumber, n.Recipient)
}
