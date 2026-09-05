package report

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/medfund/notification-service/internal/mail"
)

// ── Test doubles ─────────────────────────────────────────────────────

type stubRecipients struct {
	rows     []Recipient
	err      error
	contact  string
	contactE error
}

func (s *stubRecipients) ActiveFor(_ context.Context, _, _ string) ([]Recipient, error) {
	if s.err != nil {
		return nil, s.err
	}
	return s.rows, nil
}

func (s *stubRecipients) TenantContactEmail(_ context.Context, _ string) (string, error) {
	if s.contactE != nil {
		return "", s.contactE
	}
	return s.contact, nil
}

type stubTenantLookup struct {
	meta TenantMetadata
}

func (s stubTenantLookup) Get(_ context.Context, _ string) TenantMetadata { return s.meta }

type stubFetcher struct {
	data []byte
	err  error
}

func (s *stubFetcher) GetObject(_ context.Context, _, _ string) ([]byte, error) {
	if s.err != nil {
		return nil, s.err
	}
	return s.data, nil
}

type recordingSender struct {
	sent []mail.Message
	err  error
}

func (s *recordingSender) Send(m mail.Message) error {
	if s.err != nil {
		return s.err
	}
	s.sent = append(s.sent, m)
	return nil
}

// ── Fixtures ────────────────────────────────────────────────────────

func sampleDeliveryEvent(size int64) DeliveryEvent {
	return DeliveryEvent{
		SchemaVersion:     1,
		JobID:             "job-1",
		ScheduleID:        "sched-1",
		TenantID:          "tenant-1",
		ReportKey:         "COMMISSION_STATEMENT",
		XlsxRef:           "tenant-1/2026/08/job-1.xlsx",
		Sha256:            "abc",
		SizeBytes:         size,
		CadenceLabel:      "Monthly",
		PeriodStart:       "2026-08-01",
		PeriodEnd:         "2026-08-31",
		ReportingCurrency: "USD",
		OccurredAt:        "2026-09-01T08:00:00Z",
	}
}

func newTestDispatcher(t *testing.T, sender *recordingSender, recipients RecipientLookup,
	fetcher BlobFetcher, secret string) *Dispatcher {
	t.Helper()
	var signed *SignedURLBuilder
	if secret != "" {
		signed = NewSignedURLBuilder("http://gateway", secret, 7*24*time.Hour)
	}
	return NewDispatcher(sender, "no-reply@medfund.io", recipients,
		stubTenantLookup{meta: TenantMetadata{Name: "Acme Health"}},
		fetcher, "medfund-report-payloads",
		signed,
		NewUnsubscribeURLBuilder("http://web"))
}

// ── Tests ────────────────────────────────────────────────────────────

func TestDispatchDelivery_attachesXlsxInlineWhenUnderCap(t *testing.T) {
	sender := &recordingSender{}
	recipients := &stubRecipients{rows: []Recipient{
		{Email: "a@acme.com", IsActive: true, UnsubscribeToken: "tok-a"},
		{Email: "b@acme.com", IsActive: true, UnsubscribeToken: "tok-b"},
	}}
	fetcher := &stubFetcher{data: []byte("xlsxbytes")}
	d := newTestDispatcher(t, sender, recipients, fetcher, "secret")

	res := d.DispatchDelivery(context.Background(), sampleDeliveryEvent(1024))

	if len(res) != 2 || !res[0].Ok || !res[1].Ok {
		t.Fatalf("expected 2 successful deliveries, got %+v", res)
	}
	if len(sender.sent) != 2 {
		t.Fatalf("expected 2 sends, got %d", len(sender.sent))
	}
	if len(sender.sent[0].Attachments) != 1 {
		t.Fatalf("expected 1 attachment, got %d", len(sender.sent[0].Attachments))
	}
	att := sender.sent[0].Attachments[0]
	if !strings.HasSuffix(att.Filename, ".xlsx") {
		t.Fatalf("unexpected attachment filename: %q", att.Filename)
	}
	if !strings.Contains(att.ContentType, "spreadsheetml.sheet") {
		t.Fatalf("unexpected attachment content type: %q", att.ContentType)
	}
	if string(att.Data) != "xlsxbytes" {
		t.Fatalf("attachment data mismatch")
	}
	// Body carries the inline "attached" copy, not a download URL.
	if !strings.Contains(sender.sent[0].HTMLBody, "attached to this email") {
		t.Fatalf("body missing inline-attach copy: %s", sender.sent[0].HTMLBody)
	}
	if strings.Contains(sender.sent[0].HTMLBody, "Download XLSX") {
		t.Fatalf("body should not offer download link when attached inline")
	}
	// Subject carries tenant + report label + period.
	if !strings.Contains(sender.sent[0].Subject, "Acme Health") {
		t.Fatalf("subject missing tenant: %q", sender.sent[0].Subject)
	}
	if !strings.Contains(sender.sent[0].Subject, "Commission statement") {
		t.Fatalf("subject missing report label: %q", sender.sent[0].Subject)
	}
	// Unsubscribe link present.
	if !strings.Contains(sender.sent[0].HTMLBody, "http://web/public/unsubscribe/tok-a") {
		t.Fatalf("body missing unsubscribe link: %s", sender.sent[0].HTMLBody)
	}
	if !strings.Contains(sender.sent[1].HTMLBody, "http://web/public/unsubscribe/tok-b") {
		t.Fatalf("second body missing unsubscribe link")
	}
}

func TestDispatchDelivery_rendersSignedLinkWhenOverCap(t *testing.T) {
	sender := &recordingSender{}
	recipients := &stubRecipients{rows: []Recipient{
		{Email: "big@acme.com", IsActive: true, UnsubscribeToken: "tok"},
	}}
	// Fetcher not invoked when size > cap.
	fetcher := &stubFetcher{err: errors.New("should not be called")}
	d := newTestDispatcher(t, sender, recipients, fetcher, "secret")

	res := d.DispatchDelivery(context.Background(), sampleDeliveryEvent(MaxAttachmentBytes+1))

	if len(res) != 1 || !res[0].Ok {
		t.Fatalf("expected success, got %+v", res)
	}
	if len(sender.sent[0].Attachments) != 0 {
		t.Fatalf("expected no attachment for oversize, got %d", len(sender.sent[0].Attachments))
	}
	if !strings.Contains(sender.sent[0].HTMLBody, "Download XLSX") {
		t.Fatalf("expected download link in body, got %s", sender.sent[0].HTMLBody)
	}
	if !strings.Contains(sender.sent[0].HTMLBody, "http://gateway/api/v1/reports/scheduled/job-1/download?token=") {
		t.Fatalf("expected signed URL in body, got %s", sender.sent[0].HTMLBody)
	}
	// Link expiry note present.
	if !strings.Contains(sender.sent[0].HTMLBody, "Link valid until") {
		t.Fatalf("expected link expiry note in body")
	}
}

func TestDispatchDelivery_fallsBackToSignedLinkOnFetchFailure(t *testing.T) {
	sender := &recordingSender{}
	recipients := &stubRecipients{rows: []Recipient{
		{Email: "a@acme.com", IsActive: true},
	}}
	fetcher := &stubFetcher{err: errors.New("s3 timeout")}
	d := newTestDispatcher(t, sender, recipients, fetcher, "secret")

	res := d.DispatchDelivery(context.Background(), sampleDeliveryEvent(1024))
	if len(res) != 1 || !res[0].Ok {
		t.Fatalf("expected fallback success, got %+v", res)
	}
	if len(sender.sent[0].Attachments) != 0 {
		t.Fatalf("expected no attachment on fetch fallback")
	}
	if !strings.Contains(sender.sent[0].HTMLBody, "Download XLSX") {
		t.Fatalf("expected fallback signed-link body")
	}
}

func TestDispatchDelivery_noRecipientsIsNoop(t *testing.T) {
	sender := &recordingSender{}
	recipients := &stubRecipients{rows: nil}
	d := newTestDispatcher(t, sender, recipients, &stubFetcher{data: []byte("x")}, "secret")

	res := d.DispatchDelivery(context.Background(), sampleDeliveryEvent(1024))
	if res != nil {
		t.Fatalf("expected nil results for empty recipient list, got %+v", res)
	}
	if len(sender.sent) != 0 {
		t.Fatalf("expected no sends, got %d", len(sender.sent))
	}
}

func TestDispatchDelivery_skipsInactiveRecipients(t *testing.T) {
	sender := &recordingSender{}
	recipients := &stubRecipients{rows: []Recipient{
		{Email: "off@acme.com", IsActive: false},
		{Email: "on@acme.com", IsActive: true},
	}}
	d := newTestDispatcher(t, sender, recipients, &stubFetcher{data: []byte("x")}, "secret")

	res := d.DispatchDelivery(context.Background(), sampleDeliveryEvent(1024))
	if len(res) != 1 || !res[0].Ok || res[0].Recipient != "on@acme.com" {
		t.Fatalf("expected 1 delivery to on@acme.com, got %+v", res)
	}
}

func TestDispatchDelivery_recipientFetchErrorSurfaces(t *testing.T) {
	sender := &recordingSender{}
	recipients := &stubRecipients{err: errors.New("tenancy down")}
	d := newTestDispatcher(t, sender, recipients, &stubFetcher{data: []byte("x")}, "secret")

	res := d.DispatchDelivery(context.Background(), sampleDeliveryEvent(1024))
	if len(res) != 1 || res[0].Err == nil {
		t.Fatalf("expected err surfaced, got %+v", res)
	}
	if len(sender.sent) != 0 {
		t.Fatalf("expected no sends, got %d", len(sender.sent))
	}
}

func TestDispatchDelivery_malformedEventDropsSilently(t *testing.T) {
	sender := &recordingSender{}
	recipients := &stubRecipients{}
	d := newTestDispatcher(t, sender, recipients, &stubFetcher{}, "secret")

	// Missing scheduleId
	res := d.DispatchDelivery(context.Background(), DeliveryEvent{TenantID: "t", ReportKey: "COMMISSION_STATEMENT"})
	if res != nil {
		t.Fatalf("expected nil for malformed event, got %+v", res)
	}
}

func TestDispatchDelivery_sendErrorIsReportedPerRecipient(t *testing.T) {
	sender := &recordingSender{err: errors.New("smtp down")}
	recipients := &stubRecipients{rows: []Recipient{
		{Email: "a@acme.com", IsActive: true},
	}}
	d := newTestDispatcher(t, sender, recipients, &stubFetcher{data: []byte("x")}, "secret")

	res := d.DispatchDelivery(context.Background(), sampleDeliveryEvent(1024))
	if len(res) != 1 || res[0].Err == nil {
		t.Fatalf("expected send error surfaced, got %+v", res)
	}
}

func TestDispatchFailure_fansOutToRecipients(t *testing.T) {
	sender := &recordingSender{}
	recipients := &stubRecipients{rows: []Recipient{
		{Email: "a@acme.com", IsActive: true},
	}}
	d := newTestDispatcher(t, sender, recipients, &stubFetcher{}, "secret")

	evt := DeliveryFailedEvent{
		SchemaVersion: 1, JobID: "job-1", ScheduleID: "sched-1", TenantID: "tenant-1",
		ReportKey: "COMMISSION_STATEMENT", PeriodStart: "2026-08-01", PeriodEnd: "2026-08-31",
		FailureStage: "SHAPE", ErrorSummary: "boom", OccurredAt: "2026-09-01T08:00:00Z",
	}
	res := d.DispatchFailure(context.Background(), evt)
	if len(res) != 1 || !res[0].Ok {
		t.Fatalf("expected 1 success, got %+v", res)
	}
	if !strings.Contains(sender.sent[0].Subject, "failed") {
		t.Fatalf("expected 'failed' in subject: %q", sender.sent[0].Subject)
	}
	if !strings.Contains(sender.sent[0].HTMLBody, "SHAPE") {
		t.Fatalf("expected failure stage in body: %q", sender.sent[0].HTMLBody)
	}
	if !strings.Contains(sender.sent[0].HTMLBody, "boom") {
		t.Fatalf("expected error summary in body: %q", sender.sent[0].HTMLBody)
	}
}

func TestDispatchFailure_fallsBackToTenantContactWhenNoRecipients(t *testing.T) {
	sender := &recordingSender{}
	recipients := &stubRecipients{rows: nil, contact: "ops@acme.com"}
	d := newTestDispatcher(t, sender, recipients, &stubFetcher{}, "secret")

	res := d.DispatchFailure(context.Background(), DeliveryFailedEvent{
		JobID: "j", ScheduleID: "s", TenantID: "t", ReportKey: "COMMISSION_STATEMENT",
	})
	if len(res) != 1 || res[0].Recipient != "ops@acme.com" {
		t.Fatalf("expected fallback to ops@acme.com, got %+v", res)
	}
}

func TestDispatchFailure_noRecipientsAndNoContactIsDrop(t *testing.T) {
	sender := &recordingSender{}
	recipients := &stubRecipients{rows: nil, contact: ""}
	d := newTestDispatcher(t, sender, recipients, &stubFetcher{}, "secret")

	res := d.DispatchFailure(context.Background(), DeliveryFailedEvent{
		JobID: "j", ScheduleID: "s", TenantID: "t", ReportKey: "COMMISSION_STATEMENT",
	})
	if res != nil {
		t.Fatalf("expected drop, got %+v", res)
	}
	if len(sender.sent) != 0 {
		t.Fatalf("expected no sends")
	}
}

func TestFilenameFor(t *testing.T) {
	cases := []struct {
		name string
		evt  DeliveryEvent
		want string
	}{
		{"period range", DeliveryEvent{ReportKey: "COMMISSION_STATEMENT",
			PeriodStart: "2026-08-01", PeriodEnd: "2026-08-31"},
			"commission_statement_2026-08-01_2026-08-31.xlsx"},
		{"single day", DeliveryEvent{ReportKey: "AGED_DEBTORS",
			PeriodStart: "2026-08-31", PeriodEnd: "2026-08-31"},
			"aged_debtors_2026-08-31.xlsx"},
		{"no period", DeliveryEvent{ReportKey: "COMMISSION_STATEMENT"},
			"commission_statement.xlsx"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := filenameFor(c.evt); got != c.want {
				t.Fatalf("want %q got %q", c.want, got)
			}
		})
	}
}
