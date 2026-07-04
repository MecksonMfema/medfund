package lifecycle

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

// ── Test doubles ─────────────────────────────────────────────────────────

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

// staticTemplates just returns the default subject/body. Real behaviour is
// covered by template package tests; here we only care that the dispatcher
// runs them through renderText / renderHTML.
type staticTemplates struct{}

func (staticTemplates) Resolve(_ context.Context, _, _ string) template.Template {
	return template.Template{
		Subject:  DefaultSubject,
		HTMLBody: DefaultHTMLBody(),
		Source:   "default",
	}
}

// fakeResolver just returns a canned recipient without hitting Postgres.
// The recipient.Resolver has its own tests; we don't re-test it here.
// We stub by using a nil pool and having the dispatcher hit our fake.
// Since Dispatcher only calls Resolver.ForMember/ForGroup, we can construct
// a stand-in by wrapping a Dispatcher that overrides the resolver.
//
// Rather than tangle interfaces, wire a real recipient.Resolver against a
// fakeDB — matches the pattern in recipient/resolver_test.go.

// ── Tests ────────────────────────────────────────────────────────────────

func TestDispatch_terminated_isSkipped(t *testing.T) {
	sender := &recordingSender{}
	// nil resolver is safe here — the terminated branch short-circuits
	// before recipient lookup.
	d := &Dispatcher{Resolver: nil, Sender: sender, Templates: staticTemplates{}, From: "no-reply@medfund"}

	res := d.Dispatch(context.Background(), Event{
		TenantID: "tnt-1", SubjectType: SubjectMember, SubjectID: "mbr-1",
		Status: "terminated",
	})
	if !res.Ok {
		t.Errorf("terminated event must succeed (skip) — got err %v", res.Err)
	}
	if len(sender.sent) != 0 {
		t.Errorf("terminated must NOT fire an email — sent: %+v", sender.sent)
	}
}

func TestDispatch_nilResolver_isConfigError(t *testing.T) {
	sender := &recordingSender{}
	d := &Dispatcher{Resolver: nil, Sender: sender, Templates: staticTemplates{}, From: "no-reply@medfund"}

	res := d.Dispatch(context.Background(), Event{
		TenantID: "tnt-1", SubjectType: SubjectMember, SubjectID: "mbr-1",
		Status: "suspended",
	})
	if res.Err == nil {
		t.Fatalf("expected recipient lookup disabled error")
	}
}

func TestDispatch_unknownSubjectType_isError(t *testing.T) {
	sender := &recordingSender{}
	d := &Dispatcher{
		Resolver:  recipient.NewResolverWithDB(nil),
		Sender:    sender,
		Templates: staticTemplates{},
		From:      "no-reply@medfund",
	}

	res := d.Dispatch(context.Background(), Event{
		TenantID: "tnt-1", SubjectType: "PLANET", SubjectID: "mbr-1", Status: "suspended",
	})
	if res.Err == nil || !strings.Contains(res.Err.Error(), "unknown subjectType") {
		t.Fatalf("expected unknown subjectType error, got: %v", res.Err)
	}
}

func TestDispatch_suspendedMember_sendsSuspendedCopy(t *testing.T) {
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
		TenantID: "tnt-1", SubjectType: SubjectMember, SubjectID: "mbr-1",
		Status: "suspended", Reason: "ARREARS_ESCALATION",
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
	if !strings.Contains(msg.Subject, "suspended") {
		t.Errorf("subject must mention suspension: %q", msg.Subject)
	}
	if !strings.Contains(msg.HTMLBody, "Jane Doe") {
		t.Errorf("body must greet by name: %q", msg.HTMLBody)
	}
}

func TestDispatch_deactivatedGroup_hitsGroupLookup(t *testing.T) {
	fake := newFakeDB().
		on("FROM public.tenants", "tenant_first_medfund").
		on("FROM tenant_first_medfund.groups",
			"11111111-1111-1111-1111-111111111111", "LIAISON",
			"fallback@acme.test", "Acme Corp").
		on("FROM tenant_first_medfund.group_liaisons",
			"liaison@acme.test", "Lia", "Ison")
	sender := &recordingSender{}
	d := &Dispatcher{
		Resolver:  recipient.NewResolverWithDB(fake),
		Sender:    sender,
		Templates: staticTemplates{},
		From:      "no-reply@medfund",
	}

	res := d.Dispatch(context.Background(), Event{
		TenantID: "tnt-1", SubjectType: SubjectGroup, SubjectID: "grp-1",
		Status: "deactivated",
	})
	if !res.Ok {
		t.Fatalf("expected success, got err %v", res.Err)
	}
	if sender.sent[0].To != "liaison@acme.test" {
		t.Errorf("expected liaison delivery, got %q", sender.sent[0].To)
	}
	if !strings.Contains(sender.sent[0].Subject, "deactivated") {
		t.Errorf("subject must mention deactivation: %q", sender.sent[0].Subject)
	}
}

func TestDispatch_activeWithArrearsClearedReason_usesReactivatedCopy(t *testing.T) {
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
		TenantID: "tnt-1", SubjectType: SubjectMember, SubjectID: "mbr-1",
		Status: "active", Reason: "ARREARS_CLEARED",
	})
	if !res.Ok {
		t.Fatalf("expected success, got err %v", res.Err)
	}
	if !strings.Contains(sender.sent[0].Subject, "reactivated") {
		t.Errorf("subject must mention reactivation: %q", sender.sent[0].Subject)
	}
	// Body must mention the balance clearing so payers understand why
	// they're seeing this after a payment.
	if !strings.Contains(sender.sent[0].HTMLBody, "outstanding balance has cleared") {
		t.Errorf("body must explain why the reactivation happened: %q", sender.sent[0].HTMLBody)
	}
}

func TestDispatch_senderFailure_bubblesUpAsResult(t *testing.T) {
	fake := newFakeDB().
		on("FROM public.tenants", "tenant_first_medfund").
		on("FROM tenant_first_medfund.members",
			"jane@acme.test", "Jane", "Doe")
	sender := &recordingSender{err: errors.New("smtp dead")}
	d := &Dispatcher{
		Resolver:  recipient.NewResolverWithDB(fake),
		Sender:    sender,
		Templates: staticTemplates{},
		From:      "no-reply@medfund",
	}

	res := d.Dispatch(context.Background(), Event{
		TenantID: "tnt-1", SubjectType: SubjectMember, SubjectID: "mbr-1",
		Status: "suspended",
	})
	if res.Ok {
		t.Fatalf("expected failure result when SMTP fails")
	}
	if res.Err == nil || !strings.Contains(res.Err.Error(), "smtp dead") {
		t.Errorf("expected SMTP error to propagate, got %v", res.Err)
	}
}

// ── shared minimal fakeDB ────────────────────────────────────────────────

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
