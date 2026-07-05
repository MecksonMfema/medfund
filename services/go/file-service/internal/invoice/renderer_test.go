package invoice

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/medfund/file-service/internal/contributions"
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
		// Default recipient label when RecipientLabel isn't set on the
		// payload — the resolver falls back to "Valued group" for a
		// group-scoped invoice. Was "Group <uuid-prefix>" in v0; the
		// current defaultRecipientLabel returns the generic label.
		"Valued group",
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
	// Same default-recipient fallback as the group case — see
	// defaultRecipientLabel for the current wording.
	if !strings.Contains(string(html), "Valued member") {
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

// ─── Money formatter regression tests ───────────────────────────────
//
// These guard the core of the "4 decimal places → 2 decimal places"
// fix. If someone ever swaps %.2f back to Sprintf("%s", ...) or drops
// the empty-string handling, one of these cases fails loudly.

func TestMoney2dpStr(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		{"115.0000", "115.00"},   // the exact bug shape from the reported PDF
		{"0", "0.00"},             // zero must not render blank on a total
		{"", "0.00"},              // empty falls through to zero, not to ""
		{"65.5", "65.50"},         // pad to two decimals
		{"1234.567", "1234.57"},   // round half to even (Go default)
		{"not-a-number", "not-a-number"}, // unparseable passes through so bad data isn't silently zeroed
	}
	for _, tc := range cases {
		got := money2dpStr(tc.in)
		if got != tc.want {
			t.Errorf("money2dpStr(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestMoney2dpNum(t *testing.T) {
	cases := []struct {
		in   json.Number
		want string
	}{
		{"", ""},                  // empty nulls into blank so per-line debit/credit cells stay on one side
		{"0.0000", "0.00"},        // explicit zero still renders as 0.00 (used in balance summary)
		{"115.0000", "115.00"},
		{"50", "50.00"},
	}
	for _, tc := range cases {
		got := money2dpNum(tc.in)
		if got != tc.want {
			t.Errorf("money2dpNum(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

// pickNumber must return the first non-empty value — used to prefer the
// statement header's balance over the invoice snapshot when both exist.
func TestPickNumber(t *testing.T) {
	if s := string(pickNumber(json.Number(""), json.Number("42.00"))); s != "42.00" {
		t.Errorf("empty-first fallback: got %q", s)
	}
	if s := string(pickNumber(json.Number("10.00"), json.Number("20.00"))); s != "10.00" {
		t.Errorf("first-wins priority: got %q", s)
	}
	if s := string(pickNumber(json.Number(""), json.Number(""))); s != "" {
		t.Errorf("both-empty: got %q", s)
	}
}

// ─── buildView with rich RenderData ─────────────────────────────────
//
// The heart of the PDF-parity fix. Guards scheme grouping, dependant
// naming, per-scheme subtotals, transaction filtering, and balance
// passthrough. A regression here would produce a wrong PDF while
// leaving the formatter unit tests happy.

// sampleRenderData returns a canonical RenderPayload matching the
// shape contributions-service actually publishes. Used by buildView
// and template tests so a schema drift breaks both places at once.
func sampleRenderData() *contributions.RenderPayload {
	return &contributions.RenderPayload{
		Invoice: contributions.Invoice{
			ID:                  "582e546c-6786-4a80-8f2f-346b58dd40dc",
			InvoiceNumber:       "INV-008873",
			GroupID:             "f235b452-50d1-43e1-930c-0e93be3279fa",
			CurrencyCode:        "USD",
			TotalAmount:         "115.0000",
			PeriodStart:         "2026-08-01",
			PeriodEnd:           "2026-08-31",
			DueDate:             "2026-09-30",
			OpeningBalance:      "0.0000",
			ClosingBalance:      "115.0000",
			AdjustmentsInWindow: "0.0000",
		},
		Statement: contributions.Statement{
			Header: contributions.StatementHeader{
				TargetType:     "GROUP",
				TargetName:     "Test group",
				CurrencyCode:   "USD",
				OpeningBalance: "0.0000",
				ClosingBalance: "115.0000",
				TotalCharges:   "115.0000",
				TotalPayments:  "0",
			},
			Lines: []contributions.StatementLine{
				// Contribution lines are filtered out by buildView because
				// they're already rendered under the per-scheme detail.
				{Type: "CONTRIBUTION", Description: "member contrib", Debit: "65.0000", RunningBalance: "65.0000"},
				{Type: "CONTRIBUTION", Description: "dep contrib", Debit: "50.0000", RunningBalance: "115.0000"},
				// Non-CONTRIBUTION lines survive the filter and land in the
				// Transactions section of the PDF.
				{Date: "2026-07-15T10:00:00Z", Type: "TRANSACTION", Description: "opening deposit",
					Reference: "TX-1", Credit: "50.0000", RunningBalance: "65.0000"},
			},
		},
		Contributions: []contributions.ContributionRow{
			{MemberNumber: "MBR-856182", MemberName: "Methuseli Mfema",
				PersonType: "MEMBER", SchemeName: "Test Scheme Edited",
				InsuranceLine: "HEALTH", AgeBand: "Adult", Amount: "65.0000"},
			{MemberNumber: "MBR-856182", MemberName: "Methuseli Mfema",
				PersonType: "DEPENDANT", DependantName: "James Hetfield",
				SchemeName: "Test Scheme Edited", InsuranceLine: "HEALTH",
				AgeBand: "Child", Amount: "50.0000"},
		},
	}
}

func TestBuildView_richPayload_populatesSnapshotSections(t *testing.T) {
	p := Payload{
		InvoiceNumber:  "INV-008873",
		CurrencyCode:   "USD",
		TotalAmount:    "115.0000",
		PeriodStart:    "2026-08-01",
		PeriodEnd:      "2026-08-31",
		DueDate:        "2026-09-30",
		IssuedDate:     "2026-07-01",
		RecipientLabel: "Test group",
		RenderData:     sampleRenderData(),
	}
	v := buildView(p)

	if !v.HasSnapshot {
		t.Fatal("expected HasSnapshot=true when RenderData is set")
	}
	if v.OpeningBalance != "0.00" {
		t.Errorf("OpeningBalance = %q, want 0.00", v.OpeningBalance)
	}
	if v.ClosingBalance != "115.00" {
		t.Errorf("ClosingBalance = %q, want 115.00", v.ClosingBalance)
	}
	if v.TotalCharges != "115.00" {
		t.Errorf("TotalCharges = %q, want 115.00", v.TotalCharges)
	}
	if v.AmountDue != "115.00" {
		t.Errorf("AmountDue = %q, want 115.00", v.AmountDue)
	}

	// Scheme grouping: two contribution rows on one scheme → one
	// SchemeGroup with two rows and a computed subtotal.
	if len(v.Schemes) != 1 {
		t.Fatalf("expected 1 scheme, got %d", len(v.Schemes))
	}
	sch := v.Schemes[0]
	if sch.SchemeName != "Test Scheme Edited" {
		t.Errorf("scheme name = %q", sch.SchemeName)
	}
	if len(sch.Rows) != 2 {
		t.Fatalf("expected 2 rows in scheme, got %d", len(sch.Rows))
	}
	if sch.Rows[0].Name != "Methuseli Mfema" || sch.Rows[0].Amount != "65.00" {
		t.Errorf("member row = %+v", sch.Rows[0])
	}
	// Dependant row must show the dependant's name and a "· dep" reference suffix.
	if sch.Rows[1].Name != "James Hetfield" {
		t.Errorf("dependant name = %q, want James Hetfield", sch.Rows[1].Name)
	}
	if !strings.HasSuffix(sch.Rows[1].Reference, "· dep") {
		t.Errorf("dependant reference = %q, want suffix '· dep'", sch.Rows[1].Reference)
	}
	if sch.Subtotal != "115.00" {
		t.Errorf("scheme subtotal = %q, want 115.00", sch.Subtotal)
	}

	// Transactions section: CONTRIBUTION lines filtered out, only the
	// TRANSACTION line survives. Formatted date, 2dp credit.
	if len(v.Transactions) != 1 {
		t.Fatalf("expected 1 transaction after filtering CONTRIBUTION, got %d", len(v.Transactions))
	}
	tx := v.Transactions[0]
	if tx.Credit != "50.00" || tx.Debit != "" {
		t.Errorf("transaction debit/credit = (%q,%q), want ('','50.00')", tx.Debit, tx.Credit)
	}
	if tx.Date != "15 Jul 2026" {
		t.Errorf("transaction date = %q, want '15 Jul 2026'", tx.Date)
	}
}

// ─── Bookend row routing (double-entry ledger) ──────────────────────
//
// The contributions-service emits OPENING_BALANCE and CLOSING_BALANCE
// marker rows so the PDF renders in proper b/f + c/f format. Tests
// below pin the routing in buildView: bookends land in OpeningRow /
// ClosingRow (rendered as visually-distinct bookend rows on the
// template); they must NOT leak into the general Transactions list
// (would double-render the balance on the row list AND on the summary).

func TestBuildView_bookendLines_routeToOpeningAndClosingRow(t *testing.T) {
	rd := sampleRenderData()
	rd.Statement.Lines = append([]contributions.StatementLine{
		{
			Date:           "2026-08-01T00:00:00Z",
			Type:           "OPENING_BALANCE",
			Description:    "Balance brought forward",
			RunningBalance: "125.0000",
		},
	}, rd.Statement.Lines...)
	rd.Statement.Lines = append(rd.Statement.Lines, contributions.StatementLine{
		Date:           "2026-08-31T12:00:00Z",
		Type:           "CLOSING_BALANCE",
		Description:    "Balance carried forward",
		RunningBalance: "240.0000",
	})

	v := buildView(Payload{
		InvoiceNumber: "INV-008873", CurrencyCode: "USD",
		RenderData: rd,
	})

	if v.OpeningRow == nil {
		t.Fatal("expected OpeningRow populated when OPENING_BALANCE line present")
	}
	if v.OpeningRow.Description != "Balance brought forward" {
		t.Errorf("OpeningRow description = %q, want 'Balance brought forward'", v.OpeningRow.Description)
	}
	if v.OpeningRow.Balance != "125.00" {
		t.Errorf("OpeningRow balance = %q, want '125.00'", v.OpeningRow.Balance)
	}

	if v.ClosingRow == nil {
		t.Fatal("expected ClosingRow populated when CLOSING_BALANCE line present")
	}
	if v.ClosingRow.Balance != "240.00" {
		t.Errorf("ClosingRow balance = %q, want '240.00'", v.ClosingRow.Balance)
	}

	// Bookend rows must NOT leak into Transactions — would double-render
	// the balance on the row list AND as the bookend, which is what the
	// pre-cleanup renderer accidentally did.
	for _, tx := range v.Transactions {
		if tx.Description == "Balance brought forward" || tx.Description == "Balance carried forward" {
			t.Errorf("bookend row leaked into Transactions: %+v", tx)
		}
	}
}

func TestBuildView_noBookendLines_openingClosingRowsAreNil(t *testing.T) {
	// Legacy payloads from an older contributions-service that hasn't
	// been redeployed yet: no OPENING_BALANCE / CLOSING_BALANCE lines.
	// buildView must NOT crash; template shows opening/closing from
	// the header summary card instead (see OpeningBalance /
	// ClosingBalance fields still set from Statement.Header).
	rd := sampleRenderData()
	v := buildView(Payload{
		InvoiceNumber: "INV-008873", CurrencyCode: "USD",
		RenderData: rd,
	})

	if v.OpeningRow != nil {
		t.Errorf("OpeningRow should be nil for legacy payload without bookend lines, got %+v", v.OpeningRow)
	}
	if v.ClosingRow != nil {
		t.Errorf("ClosingRow should be nil for legacy payload without bookend lines, got %+v", v.ClosingRow)
	}
	// Header fallback still populated.
	if v.OpeningBalance != "0.00" || v.ClosingBalance != "115.00" {
		t.Errorf("header fallback balances wrong: opening=%q closing=%q", v.OpeningBalance, v.ClosingBalance)
	}
}

// Fallback path: without RenderData, HasSnapshot must be false so the
// template renders the legacy single-line summary. This is what keeps
// dev/test invocations of file-service usable when contributions-service
// is unreachable.
func TestBuildView_noRenderData_fallsBackToSummary(t *testing.T) {
	v := buildView(Payload{
		InvoiceNumber: "INV-000999",
		CurrencyCode:  "USD",
		TotalAmount:   "42.5",
	})
	if v.HasSnapshot {
		t.Errorf("expected HasSnapshot=false when RenderData is nil")
	}
	if v.SubtotalAmount != "42.50" || v.AmountDue != "42.50" {
		t.Errorf("fallback money = (%q,%q), want ('42.50','42.50')", v.SubtotalAmount, v.AmountDue)
	}
	if len(v.Schemes) != 0 || len(v.Transactions) != 0 {
		t.Errorf("fallback should have zero schemes/transactions, got %d/%d",
			len(v.Schemes), len(v.Transactions))
	}
}

// ─── Template render assertions ─────────────────────────────────────
//
// Executes the actual embedded template with a rich payload and asserts
// on the produced HTML. Catches regressions where the template drifts
// away from the fields buildView produces (a rename would silently
// render nothing without this test).

func TestRender_richPayload_HTMLHasAllSections(t *testing.T) {
	r, _ := NewRenderer(StubPdfGenerator{})
	html, err := r.HTML(Payload{
		InvoiceNumber:  "INV-008873",
		CurrencyCode:   "USD",
		TotalAmount:    "115.0000",
		PeriodStart:    "2026-08-01",
		PeriodEnd:      "2026-08-31",
		DueDate:        "2026-09-30",
		IssuedDate:     "2026-07-01",
		RecipientLabel: "Test group",
		RenderData:     sampleRenderData(),
	})
	if err != nil {
		t.Fatalf("html: %v", err)
	}
	body := string(html)

	for _, want := range []string{
		// Header summary-grid labels — the standard-financial layout.
		"Opening balance",
		"Closing balance",
		"Statement period",
		// Per-scheme contribution detail
		"Contributions",
		"Test Scheme Edited",          // scheme header row
		"Methuseli Mfema",             // member row
		"James Hetfield",              // dependant row
		"· dep",                       // dependant reference suffix
		// The ledger table + row content
		"Ledger",
		"opening deposit",             // transaction row description
		"USD 65.00", "USD 50.00", "USD 115.00", // 2-decimal-place formatting
		"Amount due",                  // ledger-footer grand-total label
	} {
		if !strings.Contains(body, want) {
			t.Errorf("rendered HTML missing %q", want)
		}
	}

	// Guard against the regression itself: no BigDecimal-style 4-decimal
	// amounts should ever appear in the rendered output.
	if strings.Contains(body, ".0000") {
		t.Errorf("rendered HTML still contains a 4-decimal amount:\n%s", body)
	}
}

// Snapshot=false path should render the legacy single-description table
// with two-decimal totals — used in the "contributions-service is
// down" degraded mode.
func TestRender_fallbackPayload_HTMLHasSummaryOnly(t *testing.T) {
	r, _ := NewRenderer(StubPdfGenerator{})
	html, err := r.HTML(Payload{
		InvoiceNumber: "INV-000199",
		GroupID:       "aaaaaaaa-2222-3333-4444-555555555555",
		CurrencyCode:  "USD",
		TotalAmount:   "60.0000",
		PeriodStart:   "2026-06-01",
		PeriodEnd:     "2026-06-30",
		DueDate:       "2026-07-30",
	})
	if err != nil {
		t.Fatalf("html: %v", err)
	}
	body := string(html)
	if !strings.Contains(body, "USD 60.00") {
		t.Errorf("fallback should format total to 2dp, got:\n%s", body)
	}
	if strings.Contains(body, "Balance brought forward") {
		t.Errorf("fallback should NOT render snapshot sections; got:\n%s", body)
	}
	if strings.Contains(body, ".0000") {
		t.Errorf("fallback rendered a 4-decimal amount:\n%s", body)
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
