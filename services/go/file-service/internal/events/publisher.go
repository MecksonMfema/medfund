package events

import (
	"context"
	"encoding/json"
	"log"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
)

const (
	TopicInvoicePdfReady = "medfund.contributions.invoice-pdf-ready"
	TopicReceiptPdfReady = "medfund.contributions.receipt-pdf-ready"
)

// ReceiptPdfReady is emitted after a payment receipt PDF has been
// rendered and uploaded. notification-service consumes this to fetch
// the PDF from MinIO and attach it to the receipt email.
type ReceiptPdfReady struct {
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
	// Friendly recipient name — carried through so notification-service
	// can use it in the salutation without another DB lookup.
	RecipientName string `json:"recipientName,omitempty"`
	PdfBucket     string `json:"pdfBucket"`
	PdfObjectKey  string `json:"pdfObjectKey"`
}

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

// Publisher writes PDF-ready events (invoice + receipt) to Kafka.
// Fire-and-forget — failures are logged so a flaky Kafka doesn't take
// down PDF rendering. Two writers because the kafka-go Writer is
// topic-bound at construction time.
type Publisher struct {
	invoiceWriter *kafka.Writer
	receiptWriter *kafka.Writer
}

func NewPublisher(brokers string) *Publisher {
	brokerAddrs := kafka.TCP(strings.Split(brokers, ",")...)
	return &Publisher{
		invoiceWriter: &kafka.Writer{
			Addr:                   brokerAddrs,
			Topic:                  TopicInvoicePdfReady,
			Balancer:               &kafka.LeastBytes{},
			AllowAutoTopicCreation: true,
		},
		receiptWriter: &kafka.Writer{
			Addr:                   brokerAddrs,
			Topic:                  TopicReceiptPdfReady,
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
	if err := p.invoiceWriter.WriteMessages(ctx, kafka.Message{
		Key:   []byte(e.InvoiceID),
		Value: body,
	}); err != nil {
		log.Printf("[file-service] publish InvoicePdfReady failed: %v", err)
	}
}

func (p *Publisher) PublishReceipt(ctx context.Context, e ReceiptPdfReady) {
	if e.Event == "" {
		e.Event = "RECEIPT_PDF_READY"
	}
	body, err := json.Marshal(e)
	if err != nil {
		log.Printf("[file-service] marshal ReceiptPdfReady: %v", err)
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	if err := p.receiptWriter.WriteMessages(ctx, kafka.Message{
		Key:   []byte(e.TransactionID),
		Value: body,
	}); err != nil {
		log.Printf("[file-service] publish ReceiptPdfReady failed: %v", err)
	}
}

func (p *Publisher) Close() {
	_ = p.invoiceWriter.Close()
	_ = p.receiptWriter.Close()
}
