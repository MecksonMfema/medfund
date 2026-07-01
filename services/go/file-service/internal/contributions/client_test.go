package contributions

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// canonical JSON body — mirrors what contributions-service actually
// returns via /api/v1/internal/invoices/{id}/render-payload. Kept in
// one place so schema drift breaks one test loudly instead of a dozen
// silently. BigDecimal fields serialize as JSON numbers (not strings),
// which is what tripped the client in dev — the first-cut Go struct
// had them typed as *string.
const canonicalRenderPayloadJSON = `{
  "invoice": {
    "id": "582e546c-6786-4a80-8f2f-346b58dd40dc",
    "invoiceNumber": "INV-008873",
    "groupId": "f235b452-50d1-43e1-930c-0e93be3279fa",
    "totalAmount": 115.0000,
    "currencyCode": "USD",
    "periodStart": "2026-08-01",
    "periodEnd": "2026-08-31",
    "status": "issued",
    "dueDate": "2026-09-30",
    "issuedAt": "2026-07-01T19:45:10.115194Z",
    "committedAt": "2026-07-01T19:45:10.115194Z",
    "openingBalance": 0.0000,
    "closingBalance": 115.0000,
    "paymentsInWindow": 0.0000,
    "adjustmentsInWindow": 0.0000
  },
  "statement": {
    "header": {
      "targetType": "GROUP",
      "targetId": "f235b452-50d1-43e1-930c-0e93be3279fa",
      "targetName": "Test group",
      "targetCode": "12345",
      "periodStart": "2026-08-01",
      "periodEnd": "2026-08-31",
      "currencyCode": "USD",
      "openingBalance": 0.0000,
      "closingBalance": 115.0000,
      "totalCharges": 115.0000,
      "totalPayments": 0
    },
    "lines": [
      {"date":"2026-07-01T19:45:10.152621Z","type":"CONTRIBUTION","description":"c","reference":null,"debit":65.0000,"credit":null,"runningBalance":65.0000,"sourceId":"b696528c-cf8d-47a2-9256-bdf62ee57b55"}
    ]
  },
  "contributions": [
    {"contributionId":"b696528c-cf8d-47a2-9256-bdf62ee57b55","memberNumber":"MBR-856182","memberName":"Methuseli Mfema","personType":"MEMBER","dependantName":null,"schemeName":"Test Scheme Edited","insuranceLine":"HEALTH","ageBand":"Adult","amount":65.0000,"currencyCode":"USD"}
  ]
}`

func TestFetchRenderPayload_success_decodesJSONNumbers(t *testing.T) {
	var gotPath string
	var gotHeader string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotHeader = r.Header.Get("X-Tenant-ID")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(canonicalRenderPayloadJSON))
	}))
	defer srv.Close()

	c := New(srv.URL)
	got, err := c.FetchRenderPayload(context.Background(),
		"68b43674-68d5-48d3-9d89-1aae22da743c",
		"582e546c-6786-4a80-8f2f-346b58dd40dc")
	if err != nil {
		t.Fatalf("FetchRenderPayload: %v", err)
	}

	// The URL is built from the base + invoice id — a regression here
	// (e.g. someone accidentally swaps to /invoices/ without /internal/)
	// silently 404s in prod but would fail this line.
	wantPath := "/api/v1/internal/invoices/582e546c-6786-4a80-8f2f-346b58dd40dc/render-payload"
	if gotPath != wantPath {
		t.Errorf("path = %q, want %q", gotPath, wantPath)
	}
	if gotHeader != "68b43674-68d5-48d3-9d89-1aae22da743c" {
		t.Errorf("X-Tenant-ID header = %q, want the tenant uuid", gotHeader)
	}

	// The real bug: BigDecimal serialized as JSON number. json.Number
	// captures the raw digits so 115.0000 survives round-trip and the
	// renderer can format it downstream.
	if string(got.Invoice.TotalAmount) != "115.0000" {
		t.Errorf("Invoice.TotalAmount = %q, want '115.0000'", got.Invoice.TotalAmount)
	}
	if string(got.Invoice.OpeningBalance) != "0.0000" {
		t.Errorf("Invoice.OpeningBalance = %q, want '0.0000'", got.Invoice.OpeningBalance)
	}
	if got.Statement.Header.TargetName != "Test group" {
		t.Errorf("target name = %q", got.Statement.Header.TargetName)
	}
	if len(got.Contributions) != 1 || got.Contributions[0].MemberName != "Methuseli Mfema" {
		t.Errorf("contributions decoded incorrectly: %+v", got.Contributions)
	}
}

func TestFetchRenderPayload_5xx_returnsErrorWithBody(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"detail":"boom"}`))
	}))
	defer srv.Close()

	c := New(srv.URL)
	_, err := c.FetchRenderPayload(context.Background(), "t1", "i1")
	if err == nil {
		t.Fatal("expected error on 500")
	}
	// The error message should carry both the status code and the body
	// so the file-service log reveals the failure without a re-run.
	if !strings.Contains(err.Error(), "500") || !strings.Contains(err.Error(), "boom") {
		t.Errorf("error should surface status + body, got %q", err.Error())
	}
}

func TestFetchRenderPayload_malformedJSON_returnsDecodeError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{not json`))
	}))
	defer srv.Close()

	_, err := New(srv.URL).FetchRenderPayload(context.Background(), "t", "i")
	if err == nil {
		t.Fatal("expected decode error")
	}
	if !strings.Contains(err.Error(), "decode payload") {
		t.Errorf("decode error message = %q", err.Error())
	}
}

func TestFetchRenderPayload_missingArgs_returnsErrorBeforeCall(t *testing.T) {
	// A blank tenant or invoice must fail before we touch the network —
	// makes misconfigured deploys surface at the log, not on downstream
	// contributions-service 400s.
	c := New("http://ignored")
	if _, err := c.FetchRenderPayload(context.Background(), "", "invoice-id"); err == nil {
		t.Error("empty tenantID should error")
	}
	if _, err := c.FetchRenderPayload(context.Background(), "tenant-id", ""); err == nil {
		t.Error("empty invoiceID should error")
	}
}

func TestFetchRenderPayload_missingBaseURL_returnsError(t *testing.T) {
	if _, err := New("").FetchRenderPayload(context.Background(), "t", "i"); err == nil {
		t.Error("empty baseURL should error")
	}
}
