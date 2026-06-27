package events

import "testing"

func TestParseInvoiceIssued_validGroupPayload(t *testing.T) {
	body := []byte(`{
		"event":"INVOICE_ISSUED",
		"invoiceId":"inv-1","invoiceNumber":"INV-1","tenantId":"t-1",
		"groupId":"g-1","currencyCode":"USD","totalAmount":"150.00",
		"periodStart":"2026-06-01","periodEnd":"2026-06-30","dueDate":"2026-07-30"
	}`)
	e, ok := ParseInvoiceIssued(body)
	if !ok {
		t.Fatal("expected valid parse")
	}
	if e.GroupID != "g-1" || e.MemberID != "" {
		t.Errorf("group event should have GroupID set and MemberID empty, got %+v", e)
	}
	if e.TotalAmount != "150.00" {
		t.Errorf("TotalAmount lost in parse, got %q", e.TotalAmount)
	}
}

func TestParseInvoiceIssued_validIndividualPayload(t *testing.T) {
	body := []byte(`{
		"event":"INVOICE_ISSUED",
		"invoiceId":"inv-2","invoiceNumber":"INV-2","tenantId":"t-1",
		"memberId":"m-1","currencyCode":"USD","totalAmount":"75.00",
		"periodStart":"2026-06-01","periodEnd":"2026-06-30","dueDate":"2026-07-30"
	}`)
	e, ok := ParseInvoiceIssued(body)
	if !ok {
		t.Fatal("expected valid parse")
	}
	if e.MemberID != "m-1" || e.GroupID != "" {
		t.Errorf("individual event should have MemberID set and GroupID empty, got %+v", e)
	}
}

func TestParseInvoiceIssued_rejectsMalformedJSON(t *testing.T) {
	if _, ok := ParseInvoiceIssued([]byte("not json")); ok {
		t.Fatal("expected drop on malformed json")
	}
}

func TestParseInvoiceIssued_rejectsMissingRequiredFields(t *testing.T) {
	// no invoiceId → drop, otherwise downstream lookup blows up on ""
	body := []byte(`{"invoiceNumber":"INV-1","tenantId":"t-1"}`)
	if _, ok := ParseInvoiceIssued(body); ok {
		t.Fatal("expected drop on missing invoiceId")
	}
}
