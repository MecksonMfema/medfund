package events

import (
	"context"
	"encoding/json"
	"log"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
)

// Subscriber wraps a single-topic Kafka reader. Mirrors the consumer
// pattern used by audit-service (services/go/audit-service/internal/consumer)
// — FirstOffset on initial run so we replay anything published while
// file-service was down, committed-offset on subsequent restarts.
type Subscriber struct {
	brokers string
	topic   string
	groupID string
}

func NewSubscriber(brokers, topic, groupID string) *Subscriber {
	return &Subscriber{brokers: brokers, topic: topic, groupID: groupID}
}

// Run blocks on the given context, dispatching each fetched message to
// handle. Cancel ctx to stop.
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
			log.Printf("[file-service][%s] %s", s.topic, msg)
		}),
	})
	defer r.Close()

	log.Printf("[file-service] subscribed: topic=%s group=%s", s.topic, s.groupID)
	for {
		msg, err := r.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("[file-service][%s] fetch error: %v — retrying in 3s", s.topic, err)
			select {
			case <-ctx.Done():
				return
			case <-time.After(3 * time.Second):
				continue
			}
		}
		handle(msg.Value)
		if err := r.CommitMessages(ctx, msg); err != nil {
			log.Printf("[file-service][%s] commit error: %v", s.topic, err)
		}
	}
}

// InvoiceIssued is the projection of the wire payload published by
// contributions-service. Field names mirror ContributionEventPublisher
// .InvoiceIssuedPayload exactly so the JSON unmarshal Just Works.
type InvoiceIssued struct {
	Event         string `json:"event"`
	InvoiceID     string `json:"invoiceId"`
	InvoiceNumber string `json:"invoiceNumber"`
	TenantID      string `json:"tenantId"`
	GroupID       string `json:"groupId,omitempty"`
	MemberID      string `json:"memberId,omitempty"`
	CurrencyCode  string `json:"currencyCode"`
	TotalAmount   string `json:"totalAmount"`
	PeriodStart   string `json:"periodStart"`
	PeriodEnd     string `json:"periodEnd"`
	DueDate       string `json:"dueDate"`
	// RecipientName is the resolved group/member name — used as the
	// PDF's recipient label so the document shows "Acme Corp" rather
	// than the truncated-UUID fallback "Group abc12345". Omitted on
	// legacy events; renderer falls back to the short-id label in that
	// case (see invoice.defaultRecipientLabel).
	RecipientName string `json:"recipientName,omitempty"`
}

// ParseInvoiceIssued unmarshals the Kafka message body and validates
// the minimum fields the renderer needs. Returns (event, true) on
// success; (zero, false) and logs on malformed input so the caller can
// commit-and-skip rather than crashing the whole consumer loop.
func ParseInvoiceIssued(body []byte) (InvoiceIssued, bool) {
	var e InvoiceIssued
	if err := json.Unmarshal(body, &e); err != nil {
		log.Printf("[file-service] drop malformed InvoiceIssued: %v", err)
		return InvoiceIssued{}, false
	}
	if e.InvoiceID == "" || e.InvoiceNumber == "" || e.TenantID == "" {
		log.Printf("[file-service] drop InvoiceIssued missing required fields: %+v", e)
		return InvoiceIssued{}, false
	}
	return e, true
}

// InvoicePdfDeleted is the projection of the wire payload published by
// ContributionEventPublisher.publishInvoicePdfDeleted on
// `medfund.contributions.invoice-pdf-deleted`. Carries the MinIO
// (bucket, objectKey) tuple that file-service should remove.
type InvoicePdfDeleted struct {
	Event     string `json:"event"`
	TenantID  string `json:"tenantId"`
	InvoiceID string `json:"invoiceId"`
	Bucket    string `json:"bucket"`
	ObjectKey string `json:"objectKey"`
}

// TransactionRecorded is the projection of the wire payload published
// by TransactionService.record on
// `medfund.contributions.transaction-recorded`. file-service consumes
// this to render the receipt PDF and republish ReceiptPdfReady for
// notification-service to attach + email.
type TransactionRecorded struct {
	Event             string `json:"event"`
	TransactionID     string `json:"transactionId"`
	TransactionNumber string `json:"transactionNumber"`
	TenantID          string `json:"tenantId"`
	GroupID           string `json:"groupId,omitempty"`
	MemberID          string `json:"memberId,omitempty"`
	Amount            string `json:"amount"`
	CurrencyCode      string `json:"currencyCode"`
	TransactionType   string `json:"transactionType"`
	PaymentMethod     string `json:"paymentMethod,omitempty"`
	Reference         string `json:"reference,omitempty"`
	TransactionDate   string `json:"transactionDate,omitempty"`
	// Friendly group/member name resolved by contributions-service
	// before publish. UUIDs never leak into rendered artefacts —
	// the renderer uses this string for the "Received from" line.
	RecipientName string `json:"recipientName,omitempty"`
}

// ParseTransactionRecorded unmarshals + validates the payload. Requires
// txn number, tenant, currency, amount, and at least one of groupId or
// memberId — a receipt with no owner has nowhere to route.
func ParseTransactionRecorded(body []byte) (TransactionRecorded, bool) {
	var e TransactionRecorded
	if err := json.Unmarshal(body, &e); err != nil {
		log.Printf("[file-service] drop malformed TransactionRecorded: %v", err)
		return TransactionRecorded{}, false
	}
	if e.TransactionID == "" || e.TransactionNumber == "" || e.TenantID == "" ||
		e.CurrencyCode == "" || e.Amount == "" {
		log.Printf("[file-service] drop TransactionRecorded missing required fields: %+v", e)
		return TransactionRecorded{}, false
	}
	if e.GroupID == "" && e.MemberID == "" {
		log.Printf("[file-service] drop TransactionRecorded with no owner: %+v", e)
		return TransactionRecorded{}, false
	}
	return e, true
}

// ParseInvoicePdfDeleted unmarshals the Kafka body and validates that
// the blob coordinates are populated. Same commit-and-skip semantics
// as ParseInvoiceIssued.
func ParseInvoicePdfDeleted(body []byte) (InvoicePdfDeleted, bool) {
	var e InvoicePdfDeleted
	if err := json.Unmarshal(body, &e); err != nil {
		log.Printf("[file-service] drop malformed InvoicePdfDeleted: %v", err)
		return InvoicePdfDeleted{}, false
	}
	if e.ObjectKey == "" {
		log.Printf("[file-service] drop InvoicePdfDeleted missing objectKey: %+v", e)
		return InvoicePdfDeleted{}, false
	}
	return e, true
}
