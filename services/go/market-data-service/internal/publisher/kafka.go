// Package publisher wires the market-data-service to Kafka. Every
// successful adapter fetch fans out a YieldCurveUpdated event on
// `medfund.market-data.yield-curve-updated`; tenancy-service's
// YieldCurveConsumer upserts the per-tenor rows into
// public.tenant_yield_curve with source = RBZ_AUTO / SARB_AUTO.
package publisher

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/medfund/market-data-service/internal/adapters"
	"github.com/segmentio/kafka-go"
)

// Topic is the wire topic — mirrored on the tenancy-service consumer.
const Topic = "medfund.market-data.yield-curve-updated"

// YieldCurveUpdated is the v1 wire envelope. Keep additive when
// evolving — the tenancy-service consumer decodes best-effort.
type YieldCurveUpdated struct {
	SchemaVersion int              `json:"schema_version"`
	TenantID      string           `json:"tenant_id"`
	Currency      string           `json:"currency"`
	Source        string           `json:"source"`
	EffectiveFrom string           `json:"effective_from"` // ISO date
	Points        []adapters.Point `json:"points"`
	PublishedAt   string           `json:"published_at"` // RFC3339
}

// Publisher is a thin wrapper on kafka-go so tests can substitute a
// mock writer.
type Publisher struct {
	writer *kafka.Writer
}

// New builds a Publisher targeting the given broker list.
func New(brokers string) *Publisher {
	addrs := kafka.TCP(strings.Split(brokers, ",")...)
	return &Publisher{
		writer: &kafka.Writer{
			Addr:                   addrs,
			Topic:                  Topic,
			Balancer:               &kafka.LeastBytes{},
			AllowAutoTopicCreation: true,
		},
	}
}

// Publish serialises the envelope + writes to Topic. Errors are logged
// and returned — the scheduler decides whether to retry.
func (p *Publisher) Publish(ctx context.Context, tenantID string, curve adapters.Curve, source string) error {
	if p == nil || p.writer == nil {
		return fmt.Errorf("publisher not initialised")
	}
	env := YieldCurveUpdated{
		SchemaVersion: 1,
		TenantID:      tenantID,
		Currency:      curve.Currency,
		Source:        source,
		EffectiveFrom: curve.EffectiveFrom.Format("2006-01-02"),
		Points:        curve.Points,
		PublishedAt:   time.Now().UTC().Format(time.RFC3339),
	}
	body, err := json.Marshal(env)
	if err != nil {
		return fmt.Errorf("marshal yield-curve envelope: %w", err)
	}
	sendCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := p.writer.WriteMessages(sendCtx, kafka.Message{
		Key:   []byte(tenantID + ":" + curve.Currency),
		Value: body,
	}); err != nil {
		return fmt.Errorf("write yield-curve message: %w", err)
	}
	log.Printf("[market-data] published %s %s (%d points, source=%s)",
		tenantID, curve.Currency, len(curve.Points), source)
	return nil
}

// Close releases the kafka writer. Safe on a nil publisher.
func (p *Publisher) Close() {
	if p == nil || p.writer == nil {
		return
	}
	_ = p.writer.Close()
}
