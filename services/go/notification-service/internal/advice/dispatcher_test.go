package advice

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/medfund/notification-service/internal/mail"
	"github.com/medfund/notification-service/internal/recipient"
	"github.com/medfund/notification-service/internal/template"
)

// ── Dispatch happy path — provider payee ────────────────────────────

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

type staticTemplates struct{}

func (staticTemplates) Resolve(_ context.Context, _, _ string) template.Template {
	return template.Template{Subject: DefaultSubject, HTMLBody: DefaultHTMLBody(), Source: "default"}
}

type staticLookup struct{ t Totals }

func (l staticLookup) Fetch(_ context.Context, _, _ string) (Totals, error) { return l.t, nil }

func TestDispatch_providerAdvice_endToEnd(t *testing.T) {
	fake := newFakeDB().
		on("FROM public.tenants", "tenant_first_medfund").
		on("FROM tenant_first_medfund.providers",
			ptr("billing@clinic.test"), ptr("Riverside Clinic"))
	sender := &recordingSender{}
	d := &Dispatcher{
		Resolver:  recipient.NewResolverWithDB(fake),
		Sender:    sender,
		Templates: staticTemplates{},
		Lookup: staticLookup{t: Totals{
			CarriedIn: "0.00", ClaimsPaid: "1500.00",
			CtcApplied: "0.00", AdvanceApplied: "200.00",
			TaxWithheld: "150.00", Shortfall: "0.00",
			NetDue: "1150.00",
		}},
		From: "no-reply@medfund",
	}

	res := d.Dispatch(context.Background(), Event{
		Event:        "PAYMENT_ADVICE_GENERATED",
		AdviceID:     "adv-1",
		AdviceNumber: "ADV-123456",
		PaymentRunID: "run-1",
		PayeeType:    "PROVIDER",
		ProviderID:   "prv-1",
		CurrencyCode: "USD",
		NetDueAmount: "1150.00",
		TenantID:     "tnt-1",
	})
	if !res.Ok || res.Err != nil {
		t.Fatalf("expected success, got err %v", res.Err)
	}
	if len(sender.sent) != 1 {
		t.Fatalf("expected one email, got %d", len(sender.sent))
	}
	msg := sender.sent[0]
	if msg.To != "billing@clinic.test" {
		t.Errorf("wrong recipient: %q", msg.To)
	}
	if !strings.Contains(msg.Subject, "ADV-123456") {
		t.Errorf("subject should carry advice number: %q", msg.Subject)
	}
	if !strings.Contains(msg.Subject, "USD 1150.00") {
		t.Errorf("subject should carry currency + net-due: %q", msg.Subject)
	}
	if !strings.Contains(msg.HTMLBody, "Advance payments applied") {
		t.Errorf("body should surface the advance-applied deduction row: %s", msg.HTMLBody)
	}
	if !strings.Contains(msg.HTMLBody, "Tax withheld") {
		t.Errorf("body should surface the tax-withheld deduction row: %s", msg.HTMLBody)
	}
}

func TestDispatch_memberAdvice_routesViaForMember(t *testing.T) {
	fake := newFakeDB().
		on("FROM public.tenants", "tenant_first_medfund").
		on("FROM tenant_first_medfund.members",
			"jane@acme.test", "Jane", "Doe")
	sender := &recordingSender{}
	d := &Dispatcher{
		Resolver:  recipient.NewResolverWithDB(fake),
		Sender:    sender,
		Templates: staticTemplates{},
		From:      "no-reply@medfund",
	}

	res := d.Dispatch(context.Background(), Event{
		AdviceID:     "adv-2",
		AdviceNumber: "ADV-789012",
		PaymentRunID: "run-1",
		PayeeType:    "MEMBER",
		MemberID:     "mbr-1",
		CurrencyCode: "USD",
		NetDueAmount: "50.00",
		TenantID:     "tnt-1",
	})
	if !res.Ok {
		t.Fatalf("expected success, got err %v", res.Err)
	}
	if sender.sent[0].To != "jane@acme.test" {
		t.Errorf("member advice should route to member email: %q", sender.sent[0].To)
	}
}

func TestDispatch_nilResolver_isConfigError(t *testing.T) {
	sender := &recordingSender{}
	d := &Dispatcher{Resolver: nil, Sender: sender, Templates: staticTemplates{}, From: "x"}
	res := d.Dispatch(context.Background(), Event{PayeeType: "PROVIDER", ProviderID: "prv-1"})
	if res.Err == nil {
		t.Fatalf("nil resolver must surface as config error")
	}
}

func TestDispatch_missingProviderId_surfacedAsError(t *testing.T) {
	fake := newFakeDB()
	d := &Dispatcher{
		Resolver: recipient.NewResolverWithDB(fake), Sender: &recordingSender{},
		Templates: staticTemplates{}, From: "x",
	}
	res := d.Dispatch(context.Background(), Event{PayeeType: "PROVIDER"})
	if res.Err == nil {
		t.Fatalf("PROVIDER event without providerId must error")
	}
}

func TestDispatch_smtpFailure_returnsError(t *testing.T) {
	fake := newFakeDB().
		on("FROM public.tenants", "tenant_first_medfund").
		on("FROM tenant_first_medfund.members",
			"jane@acme.test", "Jane", "Doe")
	sender := &recordingSender{err: errors.New("smtp down")}
	d := &Dispatcher{
		Resolver: recipient.NewResolverWithDB(fake), Sender: sender,
		Templates: staticTemplates{}, From: "x",
	}
	res := d.Dispatch(context.Background(), Event{
		AdviceID: "adv-3", AdviceNumber: "ADV-000001",
		PayeeType: "MEMBER", MemberID: "mbr-1",
		CurrencyCode: "USD", NetDueAmount: "10.00", TenantID: "tnt-1",
	})
	if res.Ok || res.Err == nil {
		t.Fatalf("expected failure when SMTP send fails")
	}
}

func TestNonZero(t *testing.T) {
	cases := map[string]bool{
		"":       false,
		"0":      false,
		"0.00":   false,
		"0.0000": false,
		"1.00":   true,
		"0.01":   true,
		"12.34":  true,
	}
	for in, want := range cases {
		if got := nonZero(in); got != want {
			t.Errorf("nonZero(%q) = %v, want %v", in, got, want)
		}
	}
}

// ── shared minimal fakeDB ───────────────────────────────────────────

func ptr(s string) *string { return &s }

type fakeDB struct{ responses map[string]fakeRow }

func newFakeDB() *fakeDB { return &fakeDB{responses: map[string]fakeRow{}} }

func (f *fakeDB) on(match string, values ...any) *fakeDB {
	f.responses[match] = fakeRow{values: values}
	return f
}

func (f *fakeDB) QueryRow(_ context.Context, sql string, _ ...any) pgx.Row {
	for match, row := range f.responses {
		if strings.Contains(sql, match) {
			return row
		}
	}
	return fakeRow{err: errors.New("no stub for query: " + sql)}
}

type fakeRow struct {
	values []any
	err    error
}

func (r fakeRow) Scan(dest ...any) error {
	if r.err != nil {
		return r.err
	}
	for i, v := range r.values {
		switch d := dest[i].(type) {
		case *string:
			if v == nil {
				return errors.New("nil for *string")
			}
			*d = v.(string)
		case **string:
			switch vv := v.(type) {
			case nil:
				*d = nil
			case string:
				s := vv
				*d = &s
			case *string:
				*d = vv
			default:
				return errors.New("unsupported value type for **string")
			}
		}
	}
	return nil
}
