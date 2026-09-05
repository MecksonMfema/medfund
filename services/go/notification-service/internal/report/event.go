// Package report consumes medfund.notification.report-delivery and
// medfund.notification.report-delivery-failed (Phase 17 §S2) and emails
// scheduled operational reports to per-schedule recipients configured
// on public.tenant_report_schedule_recipient.
//
// Producer is finance-service ScheduledReportOrchestrator
// (services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/ScheduledReportOrchestrator.java).
// The orchestrator uploads the rendered XLSX to MinIO
// (bucket medfund-report-payloads) and publishes the envelope below;
// this package fetches the XLSX, attaches it inline when the size is
// under MaxAttachmentBytes, otherwise renders a signed download URL,
// and delivers via the existing SMTP sender.
//
// Deploy-order per F-S9: this consumer must be live BEFORE the
// finance-service probe starts publishing, otherwise events land
// against an empty consumer group.
package report

// Topics — the same string constants the Java producer uses
// (see ReportDeliveryEvent.TOPIC, ReportDeliveryFailedEvent.TOPIC).
const (
	DeliveryTopic       = "medfund.notification.report-delivery"
	DeliveryFailedTopic = "medfund.notification.report-delivery-failed"
)

// DeliveryEvent mirrors com.medfund.shared.report.ReportDeliveryEvent.
// The XLSX bytes are NOT in the envelope — only the MinIO ref — so the
// wire payload stays under the Kafka message-size guard.
type DeliveryEvent struct {
	SchemaVersion     int    `json:"schemaVersion"`
	JobID             string `json:"jobId"`
	ScheduleID        string `json:"scheduleId"`
	TenantID          string `json:"tenantId"`
	ReportKey         string `json:"reportKey"`
	XlsxRef           string `json:"xlsxRef"`
	Sha256            string `json:"sha256"`
	SizeBytes         int64  `json:"sizeBytes"`
	CadenceLabel      string `json:"cadenceLabel"`
	PeriodStart       string `json:"periodStart"`
	PeriodEnd         string `json:"periodEnd"`
	ReportingCurrency string `json:"reportingCurrency"`
	OccurredAt        string `json:"occurredAt"`
}

// DeliveryFailedEvent mirrors com.medfund.shared.report.ReportDeliveryFailedEvent.
// Delivered to schedule recipients (falling back to the tenant contact
// email if the recipient list is empty) as a failure-alert email.
type DeliveryFailedEvent struct {
	SchemaVersion int    `json:"schemaVersion"`
	JobID         string `json:"jobId"`
	ScheduleID    string `json:"scheduleId"`
	TenantID      string `json:"tenantId"`
	ReportKey     string `json:"reportKey"`
	PeriodStart   string `json:"periodStart"`
	PeriodEnd     string `json:"periodEnd"`
	FailureStage  string `json:"failureStage"`
	ErrorSummary  string `json:"errorSummary"`
	OccurredAt    string `json:"occurredAt"`
}
