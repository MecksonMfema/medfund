package report

import (
	"context"
	"fmt"
	"log"
	"strings"

	"github.com/medfund/notification-service/internal/mail"
)

// MaxAttachmentBytes is the inline-attach cutoff (10 MB per S8).
// Larger XLSX payloads render as a signed-link email instead.
const MaxAttachmentBytes int64 = 10 * 1024 * 1024

// BlobFetcher pulls a rendered XLSX back from object storage. Matches
// storage.MinIOFetcher's GetObject signature so the concrete instance
// already used by the invoice pipeline plugs in without adaptation.
type BlobFetcher interface {
	GetObject(ctx context.Context, bucket, key string) ([]byte, error)
}

// Result carries the outcome of a single recipient send for logging.
type Result struct {
	Ok        bool
	Recipient string
	Err       error
}

// Dispatcher wires the per-schedule recipient lookup + SMTP sender +
// blob fetcher + signed-link + unsubscribe URL builders. Any nil
// dependency degrades that specific behaviour (attachment fetch, signed
// link, unsubscribe footer) rather than panicking — matches the
// "fail-open per channel" contract of the rest of notification-service.
type Dispatcher struct {
	Sender          mail.Sender
	From            string
	Recipients      RecipientLookup
	TenantLookup    TenantMetadataLookup
	Fetcher         BlobFetcher
	Bucket          string
	SignedURL       *SignedURLBuilder
	Unsubscribe     *UnsubscribeURLBuilder
}

func NewDispatcher(sender mail.Sender, from string, recipients RecipientLookup,
	tenantLookup TenantMetadataLookup, fetcher BlobFetcher, bucket string,
	signedURL *SignedURLBuilder, unsubscribe *UnsubscribeURLBuilder) *Dispatcher {
	return &Dispatcher{
		Sender:       sender,
		From:         from,
		Recipients:   recipients,
		TenantLookup: tenantLookup,
		Fetcher:      fetcher,
		Bucket:       bucket,
		SignedURL:    signedURL,
		Unsubscribe:  unsubscribe,
	}
}

// DispatchDelivery is the end-to-end pipeline for one delivery event.
// One send failure inside the recipient loop does not abort the rest —
// the return slice records the outcome per recipient. A top-level
// recipient-fetch failure returns a single Result with Err set so the
// consumer can log the fetch-level failure.
func (d *Dispatcher) DispatchDelivery(ctx context.Context, evt DeliveryEvent) []Result {
	if d == nil || d.Recipients == nil {
		return []Result{{Err: fmt.Errorf("dispatcher: recipient lookup not configured")}}
	}
	if evt.TenantID == "" || evt.ScheduleID == "" || evt.ReportKey == "" {
		log.Printf("[report] drop malformed delivery event tenant=%q schedule=%q key=%q",
			evt.TenantID, evt.ScheduleID, evt.ReportKey)
		return nil
	}

	recipients, err := d.Recipients.ActiveFor(ctx, evt.TenantID, evt.ScheduleID)
	if err != nil {
		log.Printf("[report] fetch recipients failed tenant=%s schedule=%s: %v",
			evt.TenantID, evt.ScheduleID, err)
		return []Result{{Err: fmt.Errorf("fetch recipients: %w", err)}}
	}
	if len(recipients) == 0 {
		log.Printf("[report] no active recipients for schedule=%s (skipping)", evt.ScheduleID)
		return nil
	}

	tenant := d.lookupTenant(ctx, evt.TenantID)

	// Fetch the XLSX once when we'll attach it inline; skip fetch when
	// we'll render a signed link instead.
	var xlsx []byte
	if evt.SizeBytes <= MaxAttachmentBytes && d.Fetcher != nil {
		bytesResult, ferr := d.Fetcher.GetObject(ctx, d.Bucket, evt.XlsxRef)
		if ferr != nil {
			log.Printf("[report] fetch XLSX s3://%s/%s failed: %v — falling back to signed link",
				d.Bucket, evt.XlsxRef, ferr)
			// Fall through: xlsx stays nil, the per-recipient loop will
			// render the signed-link body.
		} else {
			xlsx = bytesResult
		}
	}

	var results []Result
	for _, rcpt := range recipients {
		if !rcpt.IsActive {
			log.Printf("[report] recipient %s inactive for schedule=%s; skipping",
				rcpt.Email, evt.ScheduleID)
			continue
		}
		results = append(results, d.deliverOne(evt, tenant, rcpt, xlsx))
	}
	return results
}

// DispatchFailure emails schedule recipients (or the tenant contact
// email as fallback) an alert that the scheduled run failed.
func (d *Dispatcher) DispatchFailure(ctx context.Context, evt DeliveryFailedEvent) []Result {
	if d == nil {
		return []Result{{Err: fmt.Errorf("dispatcher not configured")}}
	}
	if evt.TenantID == "" || evt.ScheduleID == "" || evt.ReportKey == "" {
		log.Printf("[report] drop malformed failure event tenant=%q schedule=%q key=%q",
			evt.TenantID, evt.ScheduleID, evt.ReportKey)
		return nil
	}

	tenant := d.lookupTenant(ctx, evt.TenantID)

	var recipients []Recipient
	if d.Recipients != nil {
		rows, err := d.Recipients.ActiveFor(ctx, evt.TenantID, evt.ScheduleID)
		if err == nil {
			recipients = rows
		}
	}
	if len(recipients) == 0 {
		// Fall back to the tenant contact email.
		contact := ""
		if d.Recipients != nil {
			contact, _ = d.Recipients.TenantContactEmail(ctx, evt.TenantID)
		}
		if contact == "" {
			log.Printf("[report] failure event tenant=%s schedule=%s: no recipients + no contact email; dropping",
				evt.TenantID, evt.ScheduleID)
			return nil
		}
		recipients = []Recipient{{Email: contact, DisplayName: "Tenant admin", IsActive: true}}
	}

	subject, body := renderFailure(failureRenderData{
		TenantName:   displayTenantName(tenant, evt.TenantID),
		ReportLabel:  reportKeyLabel(evt.ReportKey),
		PeriodLabel:  periodLabel(evt.PeriodStart, evt.PeriodEnd),
		FailureStage: evt.FailureStage,
		ErrorSummary: evt.ErrorSummary,
	})

	var results []Result
	for _, rcpt := range recipients {
		if !rcpt.IsActive {
			continue
		}
		if err := d.send(rcpt.Email, subject, body, nil); err != nil {
			log.Printf("[report] failure email to %s failed: %v", rcpt.Email, err)
			results = append(results, Result{Recipient: rcpt.Email, Err: err})
			continue
		}
		results = append(results, Result{Ok: true, Recipient: rcpt.Email})
	}
	return results
}

func (d *Dispatcher) deliverOne(evt DeliveryEvent, tenant TenantMetadata,
	rcpt Recipient, xlsx []byte) Result {

	data := deliveryRenderData{
		TenantName:     displayTenantName(tenant, evt.TenantID),
		ReportLabel:    reportKeyLabel(evt.ReportKey),
		CadenceLabel:   cadenceLabel(evt.CadenceLabel),
		PeriodLabel:    periodLabel(evt.PeriodStart, evt.PeriodEnd),
		RecipientEmail: rcpt.Email,
	}

	var attachments []mail.Attachment
	if xlsx != nil {
		attachments = []mail.Attachment{{
			Filename:    filenameFor(evt),
			ContentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
			Data:        xlsx,
		}}
	} else {
		// Signed-link body path — either because the XLSX exceeds the
		// inline cap, or because the fetch failed and we fell through.
		if d.SignedURL == nil {
			return Result{Recipient: rcpt.Email,
				Err: fmt.Errorf("XLSX exceeds inline cap and signed url builder not configured")}
		}
		url, expiry, err := d.SignedURL.Build(evt.JobID, evt.TenantID, rcpt.Email)
		if err != nil {
			return Result{Recipient: rcpt.Email, Err: fmt.Errorf("build signed url: %w", err)}
		}
		data.DownloadUrl = url
		data.LinkExpiryFormatted = expiry.UTC().Format("2 Jan 2006 15:04 UTC")
	}

	if d.Unsubscribe != nil && rcpt.UnsubscribeToken != "" {
		data.UnsubscribeUrl = d.Unsubscribe.Build(rcpt.UnsubscribeToken)
	}

	subject, body := renderDelivery(data)
	if err := d.send(rcpt.Email, subject, body, attachments); err != nil {
		log.Printf("[report] delivery email to %s failed: %v", rcpt.Email, err)
		return Result{Recipient: rcpt.Email, Err: err}
	}
	return Result{Ok: true, Recipient: rcpt.Email}
}

func (d *Dispatcher) send(to, subject, body string, attachments []mail.Attachment) error {
	if d.Sender == nil {
		return fmt.Errorf("email sender not configured")
	}
	return d.Sender.Send(mail.Message{
		From:        d.From,
		To:          to,
		Subject:     subject,
		HTMLBody:    body,
		Attachments: attachments,
	})
}

func (d *Dispatcher) lookupTenant(ctx context.Context, tenantID string) TenantMetadata {
	if d.TenantLookup == nil {
		return TenantMetadata{}
	}
	return d.TenantLookup.Get(ctx, tenantID)
}

func displayTenantName(tenant TenantMetadata, fallback string) string {
	if tenant.Name != "" {
		return tenant.Name
	}
	if tenant.Slug != "" {
		return tenant.Slug
	}
	return fallback
}

// filenameFor produces an XLSX filename the recipient can save without
// renaming. Format: {reportkey}_{periodStart}_{periodEnd}.xlsx
func filenameFor(evt DeliveryEvent) string {
	key := strings.ToLower(evt.ReportKey)
	if evt.PeriodStart == "" {
		return key + ".xlsx"
	}
	if evt.PeriodEnd == "" || evt.PeriodEnd == evt.PeriodStart {
		return fmt.Sprintf("%s_%s.xlsx", key, evt.PeriodStart)
	}
	return fmt.Sprintf("%s_%s_%s.xlsx", key, evt.PeriodStart, evt.PeriodEnd)
}
