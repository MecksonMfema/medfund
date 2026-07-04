package arrears

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

// ── copyForKind: pure branchy function — table-driven ───────────────

func TestCopyForKind_pinsSubjectHeadlinePerKind(t *testing.T) {
	cases := []struct {
		kind             string
		wantSubjectFrag  string
		wantLeadFragment string
	}{
		{"REMINDER_PRE_SUSPENSION", "Payment reminder", "avoid a suspension"},
		{"REMINDER_PRE_DEACTIVATION", "Suspended account reminder", "before it is deactivated"},
		{"SUSPENDED_NOTICE", "Account suspended", "suspended due to overdue"},
		{"DEACTIVATED_NOTICE", "Account deactivated", "recorded as bad debt"},
		{"UNKNOWN_KIND", "Payment notice", "outstanding balance"},
	}
	for _, c := range cases {
		t.Run(c.kind, func(t *testing.T) {
			subj, lead, _ := copyForKind(c.kind, "", "5")
			if !strings.Contains(subj, c.wantSubjectFrag) {
				t.Errorf("subject headline for %s missing %q, got: %q",
					c.kind, c.wantSubjectFrag, subj)
			}
			if !strings.Contains(lead, c.wantLeadFragment) {
				t.Errorf("lead paragraph for %s missing %q, got: %q",
					c.kind, c.wantLeadFragment, lead)
			}
		})
	}
}

func TestCopyForKind_reminderPreSuspension_showsCountdown(t *testing.T) {
	_, lead, _ := copyForKind("REMINDER_PRE_SUSPENSION", "SUSPENSION", "3")
	if !strings.Contains(lead, "3 day(s)") {
		t.Errorf("reminder body must include the day count, got: %q", lead)
	}
}

// ── Dispatch happy path uses templates + sender ─────────────────────

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

func TestDispatch_memberReminder_endToEnd(t *testing.T) {
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
		Event:             "ARREARS_NOTICE",
		Kind:              "REMINDER_PRE_SUSPENSION",
		TenantID:          "tnt-1",
		SubjectType:       "MEMBER",
		SubjectID:         "mbr-1",
		CurrencyCode:      "USD",
		Balance:           "125.50",
		DaysOverdue:       "23",
		DaysUntilNextStep: "7",
		NextStep:          "SUSPENSION",
	})
	if !res.Ok || res.Err != nil {
		t.Fatalf("expected success, got err %v", res.Err)
	}
	if len(sender.sent) != 1 {
		t.Fatalf("expected one email, got %d", len(sender.sent))
	}
	msg := sender.sent[0]
	if msg.To != "jane@acme.test" {
		t.Errorf("wrong recipient: %q", msg.To)
	}
	// Subject template = "{{.HeadlineShort}} — {{.CurrencyCode}} {{.Balance}}"
	if !strings.Contains(msg.Subject, "Payment reminder") {
		t.Errorf("subject headline missing: %q", msg.Subject)
	}
	if !strings.Contains(msg.Subject, "USD 125.50") {
		t.Errorf("subject should carry currency + balance for at-a-glance triage: %q", msg.Subject)
	}
}

func TestDispatch_nilResolver_isConfigError(t *testing.T) {
	sender := &recordingSender{}
	d := &Dispatcher{Resolver: nil, Sender: sender, Templates: staticTemplates{}, From: "x"}
	res := d.Dispatch(context.Background(), Event{SubjectType: "MEMBER", SubjectID: "mbr-1"})
	if res.Err == nil {
		t.Fatalf("nil resolver must surface as config error")
	}
}

func TestDispatch_smtpFailure_returnsError(t *testing.T) {
	fake := newFakeDB().
		on("FROM public.tenants", "tenant_first_medfund").
		on("FROM tenant_first_medfund.members",
			"jane@acme.test", "Jane", "Doe")
	sender := &recordingSender{err: errors.New("smtp down")}
	d := &Dispatcher{
		Resolver:  recipient.NewResolverWithDB(fake),
		Sender:    sender,
		Templates: staticTemplates{},
		From:      "x",
	}
	res := d.Dispatch(context.Background(), Event{
		Kind: "REMINDER_PRE_SUSPENSION", TenantID: "tnt-1", SubjectType: "MEMBER", SubjectID: "mbr-1",
		CurrencyCode: "USD", Balance: "10.00", DaysOverdue: "5", DaysUntilNextStep: "5",
	})
	if res.Ok || res.Err == nil {
		t.Fatalf("expected failure when SMTP send fails")
	}
}

// ── shared minimal fakeDB (matches lifecycle/dispatcher_test.go) ────

type fakeDB struct {
	responses map[string]fakeRow
}

func newFakeDB() *fakeDB {
	return &fakeDB{responses: map[string]fakeRow{}}
}

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
			if v == nil {
				*d = nil
			} else {
				s := v.(string)
				*d = &s
			}
		}
	}
	return nil
}
