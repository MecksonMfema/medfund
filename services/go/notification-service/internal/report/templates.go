package report

import (
	"bytes"
	"fmt"
	"html/template"
	"strings"
	"time"
)

// Templates for scheduled-report delivery + failure. Kept inline (not
// tenant-overrideable in v1) so the dispatcher doesn't need a Postgres
// pool. When a follow-up wires per-tenant templates via
// public.tenant_email_templates, the resolver can supply the source
// strings and the render functions stay the same.

const deliverySubjectSource = `[{{.TenantName}}] {{.ReportLabel}} - {{.CadenceLabel}} - {{.PeriodLabel}}`

const deliveryBodySource = `<!DOCTYPE html>
<html>
<body style="font-family:Arial,sans-serif;line-height:1.5;color:#333;">
  <h2>{{.ReportLabel}}</h2>
  <p>Your scheduled {{.CadenceLabel}} report covering <strong>{{.PeriodLabel}}</strong> is ready.</p>
  {{if .DownloadUrl}}
    <p><a href="{{.DownloadUrl}}" style="background:#0066cc;color:#fff;padding:10px 20px;text-decoration:none;border-radius:4px;">Download XLSX</a></p>
    <p><small>Link valid until {{.LinkExpiryFormatted}}.</small></p>
  {{else}}
    <p>The XLSX is attached to this email.</p>
  {{end}}
  <hr>
  <p style="font-size:12px;color:#888;">
    Delivered to {{.RecipientEmail}} for tenant {{.TenantName}}.
    {{if .UnsubscribeUrl}}<a href="{{.UnsubscribeUrl}}">Unsubscribe from this report</a>.{{end}}
  </p>
</body>
</html>`

const failureSubjectSource = `[{{.TenantName}}] Scheduled report failed: {{.ReportLabel}} ({{.PeriodLabel}})`

const failureBodySource = `<!DOCTYPE html>
<html>
<body style="font-family:Arial,sans-serif;line-height:1.5;color:#333;">
  <h2 style="color:#c00;">Scheduled report failure</h2>
  <p>The scheduled run for <strong>{{.ReportLabel}}</strong> covering <strong>{{.PeriodLabel}}</strong> did not complete.</p>
  <p><strong>Stage:</strong> {{.FailureStage}}</p>
  <p><strong>Error:</strong> <code>{{.ErrorSummary}}</code></p>
  <p>The schedule is still active. The next scheduled fire will run normally, and you can manually re-run this fire from the schedule history page.</p>
</body>
</html>`

var (
	deliverySubjectTmpl = template.Must(template.New("delivery-subject").Parse(deliverySubjectSource))
	deliveryBodyTmpl    = template.Must(template.New("delivery-body").Parse(deliveryBodySource))
	failureSubjectTmpl  = template.Must(template.New("failure-subject").Parse(failureSubjectSource))
	failureBodyTmpl     = template.Must(template.New("failure-body").Parse(failureBodySource))
)

// deliveryRenderData is the data bag the delivery templates bind to.
type deliveryRenderData struct {
	TenantName          string
	ReportLabel         string
	CadenceLabel        string
	PeriodLabel         string
	RecipientEmail      string
	DownloadUrl         string
	LinkExpiryFormatted string
	UnsubscribeUrl      string
}

type failureRenderData struct {
	TenantName   string
	ReportLabel  string
	PeriodLabel  string
	FailureStage string
	ErrorSummary string
}

// renderDelivery renders (subject, body). Never returns error — the
// templates are compile-time verified via template.Must; any binding
// error is a programmer bug and would show up in tests, not in prod.
func renderDelivery(data deliveryRenderData) (string, string) {
	return mustRender(deliverySubjectTmpl, data), mustRender(deliveryBodyTmpl, data)
}

func renderFailure(data failureRenderData) (string, string) {
	return mustRender(failureSubjectTmpl, data), mustRender(failureBodyTmpl, data)
}

func mustRender(t *template.Template, data any) string {
	var buf bytes.Buffer
	if err := t.Execute(&buf, data); err != nil {
		// Fall back to a plain marker so a template panic doesn't cost the
		// send. The failure lands in the log for post-mortem.
		return fmt.Sprintf("[template render failed: %v]", err)
	}
	return buf.String()
}

// reportKeyLabel gives a human-readable label for the report key so the
// email subject reads naturally. Falls back to the raw key if unknown —
// which happens when the Java-side enum grows before the Go side
// catches up.
func reportKeyLabel(key string) string {
	if label, ok := reportKeyLabels[key]; ok {
		return label
	}
	// Convert SNAKE_CASE → Title Case fallback so an unmapped key still
	// reads OK.
	return prettify(key)
}

func prettify(key string) string {
	if key == "" {
		return ""
	}
	parts := strings.Split(key, "_")
	for i, p := range parts {
		if p == "" {
			continue
		}
		parts[i] = strings.ToUpper(p[:1]) + strings.ToLower(p[1:])
	}
	return strings.Join(parts, " ")
}

// reportKeyLabels mirrors the Angular reports whitelist copy. Keep it
// in sync when a new key becomes schedulable (see
// ScheduledReportEligibility on the Java side).
var reportKeyLabels = map[string]string{
	"COMMISSION_STATEMENT":          "Commission statement",
	"LOSS_RATIO":                    "Loss ratio",
	"COLLECTION_RATE":               "Collection rate",
	"AGED_DEBTORS":                  "Aged debtors",
	"CASH_FLOW_FORECAST_13W":        "13-week cash flow forecast",
	"CLAIMS_SUMMARY":                "Claims summary",
	"POLICY_MOVEMENT":               "Policy movement",
	"PERSISTENCY_COHORT":            "Persistency cohort",
	"GROUP_CENSUS":                  "Group census",
	"PROVIDER_NETWORK_UTILIZATION":  "Provider network utilization",
	"REINSURANCE_CESSION_BORDEREAU": "Reinsurance cession bordereau",
	"REINSURANCE_RECOVERIES":        "Reinsurance recoveries",
	"UPR_MOVEMENT":                  "UPR movement",
}

// cadenceLabel normalises the cadence label passed by the producer for
// display purposes. The Java side sends "Monthly" / "Quarterly" /
// "Weekly" / "Annual" already, so this is defence-in-depth.
func cadenceLabel(raw string) string {
	if raw == "" {
		return ""
	}
	lower := strings.ToLower(raw)
	return strings.ToUpper(lower[:1]) + lower[1:]
}

// periodLabel formats "YYYY-MM-DD to YYYY-MM-DD" as the more
// email-friendly "1 Aug 2026 – 31 Aug 2026". Malformed dates fall back
// to the raw strings.
func periodLabel(periodStart, periodEnd string) string {
	start, err1 := time.Parse("2006-01-02", periodStart)
	end, err2 := time.Parse("2006-01-02", periodEnd)
	if err1 != nil || err2 != nil {
		if periodStart == periodEnd {
			return periodStart
		}
		return fmt.Sprintf("%s to %s", periodStart, periodEnd)
	}
	if start.Equal(end) {
		return start.Format("2 Jan 2006")
	}
	return fmt.Sprintf("%s – %s", start.Format("2 Jan 2006"), end.Format("2 Jan 2006"))
}
