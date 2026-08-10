package events

import (
	"context"
	"encoding/json"
	"log"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
)

const TopicPaymentGatewaySettled = "medfund.payments.gateway.settled"

// SettledEvent is emitted after each item has been "processed" by the
// stubbed provider. Consumed by finance-service's
// PaymentGatewaySettledConsumer which flips the referenced Payment
// row to paid.
type SettledEvent struct {
	Event         string `json:"event"`
	ItemID        string `json:"itemId"`
	PaymentID     string `json:"paymentId"`
	TenantID      string `json:"tenantId"`
	TransactionID string `json:"transactionId"`
	ProviderRef   string `json:"providerRef"`
	Status        string `json:"status"`
	Amount        string `json:"amount"`
	CurrencyCode  string `json:"currencyCode"`
}

// Publisher writes SettledEvents to Kafka. Fire-and-forget — failures
// are logged so a flaky Kafka doesn't take the consumer loop down.
type Publisher struct {
	writer *kafka.Writer
}

func NewPublisher(brokers string) *Publisher {
	return &Publisher{
		writer: &kafka.Writer{
			Addr:                   kafka.TCP(strings.Split(brokers, ",")...),
			Topic:                  TopicPaymentGatewaySettled,
			Balancer:               &kafka.LeastBytes{},
			AllowAutoTopicCreation: true,
		},
	}
}

func (p *Publisher) PublishSettled(ctx context.Context, e SettledEvent) {
	if e.Event == "" {
		e.Event = "PAYMENT_GATEWAY_SETTLED"
	}
	body, err := json.Marshal(e)
	if err != nil {
		log.Printf("[payment-gateway] marshal SettledEvent: %v", err)
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	if err := p.writer.WriteMessages(ctx, kafka.Message{
		Key:   []byte(e.ItemID),
		Value: body,
	}); err != nil {
		log.Printf("[payment-gateway] publish SettledEvent failed: %v", err)
	}
}

func (p *Publisher) Close() {
	_ = p.writer.Close()
}
