package events

import (
	"context"
	"encoding/json"
	"log"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/segmentio/kafka-go"
)

const topicSecurityEvents = "medfund.security.events"

// SecurityEvent matches the JSON shape consumed by the audit service.
type SecurityEvent struct {
	ID         string `json:"id"`
	TenantID   string `json:"tenantId"`
	EventType  string `json:"eventType"`
	UserID     string `json:"userId"`
	ActorEmail string `json:"actorEmail"`
	IPAddress  string `json:"ipAddress"`
	UserAgent  string `json:"userAgent"`
	Details    string `json:"details"`
	Timestamp  string `json:"timestamp"`
}

// Publisher sends security events to the Kafka topic consumed by the audit service.
// All writes are fire-and-forget (non-blocking) so they never slow down auth requests.
type Publisher struct {
	writer *kafka.Writer
}

func NewPublisher(brokers string) *Publisher {
	return &Publisher{
		writer: &kafka.Writer{
			Addr:                   kafka.TCP(strings.Split(brokers, ",")...),
			Topic:                  topicSecurityEvents,
			Balancer:               &kafka.LeastBytes{},
			AllowAutoTopicCreation: true,
		},
	}
}

// Publish serializes the event and sends it in a goroutine so the caller is
// never blocked. Errors are logged but not propagated.
func (p *Publisher) Publish(event SecurityEvent) {
	if event.ID == "" {
		event.ID = uuid.New().String()
	}
	if event.Timestamp == "" {
		event.Timestamp = time.Now().UTC().Format(time.RFC3339Nano)
	}

	body, err := json.Marshal(event)
	if err != nil {
		return
	}

	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		if err := p.writer.WriteMessages(ctx, kafka.Message{
			Key:   []byte(event.UserID),
			Value: body,
		}); err != nil {
			log.Printf("[gateway-events] publish %s failed: %v", event.EventType, err)
		}
	}()
}

func (p *Publisher) Close() {
	_ = p.writer.Close()
}
