package invoice

import (
	"context"
	"strings"
	"testing"
)

func TestRender_groupPayload_HTMLContainsExpectedFields(t *testing.T) {
	r, err := NewRenderer(StubPdfGenerator{})
	if err != nil {
		t.Fatalf("renderer: %v", err)
	}
	html, err := r.HTML(Payload{
		InvoiceNumber: "INV-000123",
		GroupID:       "11111111-2222-3333-4444-555555555555",
		CurrencyCode:  "USD",
		TotalAmount:   "150.00",
		PeriodStart:   "2026-06-01",
		PeriodEnd:     "2026-06-30",
		DueDate:       "2026-07-30",
		IssuedDate:    "2026-06-27",
	})
	if err != nil {
		t.Fatalf("html: %v", err)
	}
	body := string(html)
	for _, want := range []string{
		"INV-000123",
		"USD",
		"150.00",
		"2026-06-30",
		"2026-07-30",
		// Group default label uses first 8 chars of UUID
		"Group 11111111",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("rendered HTML missing %q\n---\n%s\n---", want, body)
		}
	}
}

func TestRender_individualPayload_HTMLLabelsMember(t *testing.T) {
	r, _ := NewRenderer(StubPdfGenerator{})
	html, err := r.HTML(Payload{
		InvoiceNumber: "INV-000124",
		MemberID:      "abcdef01-2222-3333-4444-555555555555",
		CurrencyCode:  "ZAR",
		TotalAmount:   "75.00",
		PeriodStart:   "2026-06-01",
		PeriodEnd:     "2026-06-30",
		DueDate:       "2026-07-30",
	})
	if err != nil {
		t.Fatalf("html: %v", err)
	}
	if !strings.Contains(string(html), "Member abcdef01") {
		t.Errorf("individual recipient label missing in HTML:\n%s", html)
	}
}

func TestRender_callerSuppliedLabel_isPreserved(t *testing.T) {
	r, _ := NewRenderer(StubPdfGenerator{})
	html, err := r.HTML(Payload{
		InvoiceNumber:  "INV-000125",
		GroupID:        "11111111-2222-3333-4444-555555555555",
		CurrencyCode:   "USD",
		TotalAmount:    "100.00",
		PeriodStart:    "2026-06-01",
		PeriodEnd:      "2026-06-30",
		DueDate:        "2026-07-30",
		RecipientLabel: "Acme Holdings (Pty) Ltd",
	})
	if err != nil {
		t.Fatalf("html: %v", err)
	}
	if !strings.Contains(string(html), "Acme Holdings (Pty) Ltd") {
		t.Errorf("explicit RecipientLabel should be preserved verbatim:\n%s", html)
	}
}

func TestRender_PDFstub_returnsHeaderBytes(t *testing.T) {
	r, _ := NewRenderer(StubPdfGenerator{})
	pdf, err := r.Render(context.Background(), Payload{
		InvoiceNumber: "INV-000126",
		GroupID:       "1",
		CurrencyCode:  "USD",
		TotalAmount:   "50.00",
		PeriodStart:   "2026-06-01",
		PeriodEnd:     "2026-06-30",
		DueDate:       "2026-07-30",
	})
	if err != nil {
		t.Fatalf("render: %v", err)
	}
	if !strings.HasPrefix(string(pdf), "%PDF-") {
		t.Errorf("expected PDF header, got %q", string(pdf))
	}
}
