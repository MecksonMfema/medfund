package report

import (
	"strings"
	"testing"
)

func TestRenderDelivery_populatesBothVariants(t *testing.T) {
	subj, body := renderDelivery(deliveryRenderData{
		TenantName:          "Acme Health",
		ReportLabel:         "Commission statement",
		CadenceLabel:        "Monthly",
		PeriodLabel:         "1 Aug 2026 – 31 Aug 2026",
		RecipientEmail:      "cfo@acme.com",
		DownloadUrl:         "https://gw/api/v1/reports/scheduled/job-1/download?token=abc",
		LinkExpiryFormatted: "8 Sep 2026 08:00 UTC",
		UnsubscribeUrl:      "https://web/public/unsubscribe/tok",
	})
	if !strings.Contains(subj, "Acme Health") ||
		!strings.Contains(subj, "Commission statement") ||
		!strings.Contains(subj, "Monthly") ||
		!strings.Contains(subj, "1 Aug 2026 – 31 Aug 2026") {
		t.Fatalf("subject missing fields: %q", subj)
	}
	if !strings.Contains(body, "Download XLSX") {
		t.Fatalf("body missing download link CTA: %s", body)
	}
	if !strings.Contains(body, "cfo@acme.com") {
		t.Fatalf("body missing recipient email: %s", body)
	}
	if !strings.Contains(body, "https://web/public/unsubscribe/tok") {
		t.Fatalf("body missing unsubscribe url: %s", body)
	}
	if !strings.Contains(body, "8 Sep 2026 08:00 UTC") {
		t.Fatalf("body missing link expiry: %s", body)
	}
}

func TestRenderDelivery_attachedVariantHidesDownloadLink(t *testing.T) {
	_, body := renderDelivery(deliveryRenderData{
		TenantName:     "Acme",
		ReportLabel:    "Loss ratio",
		CadenceLabel:   "Quarterly",
		PeriodLabel:    "Q2 2026",
		RecipientEmail: "cfo@acme.com",
	})
	if strings.Contains(body, "Download XLSX") {
		t.Fatalf("body should not offer download link when attached inline")
	}
	if !strings.Contains(body, "attached to this email") {
		t.Fatalf("body missing inline-attach copy: %s", body)
	}
}

func TestRenderFailure_populatesAllFields(t *testing.T) {
	subj, body := renderFailure(failureRenderData{
		TenantName:   "Acme",
		ReportLabel:  "Commission statement",
		PeriodLabel:  "Aug 2026",
		FailureStage: "MINIO_UPLOAD",
		ErrorSummary: "connection reset by peer",
	})
	if !strings.Contains(subj, "Scheduled report failed") ||
		!strings.Contains(subj, "Acme") ||
		!strings.Contains(subj, "Commission statement") {
		t.Fatalf("subject missing fields: %q", subj)
	}
	for _, want := range []string{"MINIO_UPLOAD", "connection reset by peer", "Commission statement"} {
		if !strings.Contains(body, want) {
			t.Fatalf("body missing %q: %s", want, body)
		}
	}
}

func TestReportKeyLabel_knownAndUnknown(t *testing.T) {
	if got := reportKeyLabel("COMMISSION_STATEMENT"); got != "Commission statement" {
		t.Fatalf("known key mislabeled: %q", got)
	}
	if got := reportKeyLabel("NEW_UNMAPPED_KEY"); got != "New Unmapped Key" {
		t.Fatalf("unknown key not prettified: %q", got)
	}
	if got := reportKeyLabel(""); got != "" {
		t.Fatalf("empty key returned %q, want empty", got)
	}
}

func TestPeriodLabel(t *testing.T) {
	cases := []struct {
		start, end, want string
	}{
		{"2026-08-01", "2026-08-31", "1 Aug 2026 – 31 Aug 2026"},
		{"2026-08-31", "2026-08-31", "31 Aug 2026"},
		{"bad", "date", "bad to date"},
		{"same", "same", "same"},
	}
	for _, c := range cases {
		if got := periodLabel(c.start, c.end); got != c.want {
			t.Fatalf("periodLabel(%q, %q) = %q, want %q", c.start, c.end, got, c.want)
		}
	}
}

func TestCadenceLabel(t *testing.T) {
	cases := map[string]string{
		"":          "",
		"Monthly":   "Monthly",
		"QUARTERLY": "Quarterly",
		"weekly":    "Weekly",
	}
	for in, want := range cases {
		if got := cadenceLabel(in); got != want {
			t.Fatalf("cadenceLabel(%q) = %q want %q", in, got, want)
		}
	}
}
