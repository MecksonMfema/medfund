package events

import (
	"context"
	"encoding/json"
	"log"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
)

const TopicInvoicePdfReady = "medfund.contributions.invoice-pdf-ready"

// InvoicePdfReady is emitted after the PDF has been rendered and uploaded.
// notification-service consumes this to fetch the PDF and email it to
// the appropriate recipient.
type InvoicePdfReady struct {
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

	// PdfBucket + PdfObjectKey locate the rendered PDF in MinIO so
	// notification-service can download and attach it. Kept as bucket +
	// key instead of a presigned URL because the URL would expire long
	// before the consumer retries on a failure.
	PdfBucket    string `json:"pdfBucket"`
	PdfObjectKey string `json:"pdfObjectKey"`
}

// Publisher writes InvoicePdfReady events to Kafka. Fire-and-forget —
// failures are logged so a flaky Kafka doesn't take down PDF rendering.
type Publisher struct {
	writer *kafka.Writer
}

func NewPublisher(brokers string) *Publisher {
	return &Publisher{
		writer: &kafka.Writer{
			Addr:                   kafka.TCP(strings.Split(brokers, ",")...),
			Topic:                  TopicInvoicePdfReady,
			Balancer:               &kafka.LeastBytes{},
			AllowAutoTopicCreation: true,
		},
	}
}

func (p *Publisher) Publish(ctx context.Context, e InvoicePdfReady) {
	if e.Event == "" {
		e.Event = "INVOICE_PDF_READY"
	}
	body, err := json.Marshal(e)
	if err != nil {
		log.Printf("[file-service] marshal InvoicePdfReady: %v", err)
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	if err := p.writer.WriteMessages(ctx, kafka.Message{
		Key:   []byte(e.InvoiceID),
		Value: body,
	}); err != nil {
		log.Printf("[file-service] publish InvoicePdfReady failed: %v", err)
	}
}

func (p *Publisher) Close() { _ = p.writer.Close() }
