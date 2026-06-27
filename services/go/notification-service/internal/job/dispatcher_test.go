package job

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/medfund/notification-service/internal/mail"
	"github.com/medfund/notification-service/internal/template"
)

type fakeLookup struct {
	user StaffUser
	err  error
}

func (f fakeLookup) ForActor(_ context.Context, _ string) (StaffUser, error) {
	return f.user, f.err
}

type fakeResolver struct{ subj, body string }

func (f fakeResolver) Resolve(_ context.Context, _, _ string) template.Template {
	return template.Template{Subject: f.subj, HTMLBody: f.body, Source: "default"}
}

func newTestDispatcher(t *testing.T, sender mail.Sender) *Dispatcher {
	t.Helper()
	d, err := NewDispatcher(
		fakeLookup{user: StaffUser{Email: "ops@medfund", DisplayName: "Ops Person"}},
		sender,
		fakeResolver{subj: DefaultSubject, body: DefaultHTMLBody()},
		"no-reply@medfund.healthcare",
		30*time.Second,
	)
	if err != nil {
		t.Fatal(err)
	}
	return d
}

func TestDispatch_skipsScheduledTrigger(t *testing.T) {
	sender := &mail.MockSender{}
	d := newTestDispatcher(t, sender)

	res := d.Dispatch(context.Background(), Event{
		TriggerKind: "schedule", TriggeredBy: "uuid-1", DurationMs: "60000",
	})

	if !res.Skipped {
		t.Errorf("scheduled trigger should be skipped, got %+v", res)
	}
	if len(sender.Sent) != 0 {
		t.Errorf("no email expected for scheduled trigger")
	}
}

func TestDispatch_skipsBelowDurationThreshold(t *testing.T) {
	sender := &mail.MockSender{}
	d := newTestDispatcher(t, sender)

	res := d.Dispatch(context.Background(), Event{
		TriggerKind: "manual", TriggeredBy: "uuid-1", DurationMs: "5000", // 5s
	})

	if !res.Skipped {
		t.Errorf("short job should be skipped, got %+v", res)
	}
}

func TestDispatch_sendsForLongManualRun(t *testing.T) {
	sender := &mail.MockSender{}
	d := newTestDispatcher(t, sender)

	res := d.Dispatch(context.Background(), Event{
		RunID: "r-1", TriggerKind: "manual", TriggeredBy: "uuid-1",
		Kind: "BILLING_COMMIT", Status: "SUCCESS",
		DurationMs: "90000", // 1m30s
	})

	if !res.Ok || res.Recipient != "ops@medfund" {
		t.Errorf("expected Ok send to ops@medfund, got %+v", res)
	}
	if len(sender.Sent) != 1 {
		t.Fatalf("expected 1 sent message, got %d", len(sender.Sent))
	}
	msg := sender.Sent[0]
	if !strings.Contains(msg.Subject, "billing commit") || !strings.Contains(msg.Subject, "finished") {
		t.Errorf("subject should be friendly for SUCCESS, got %q", msg.Subject)
	}
	if !strings.Contains(msg.HTMLBody, "Ops Person") {
		t.Errorf("body should greet the actor, got:\n%s", msg.HTMLBody)
	}
}

func TestDispatch_failedJobMentionsError(t *testing.T) {
	sender := &mail.MockSender{}
	d := newTestDispatcher(t, sender)

	d.Dispatch(context.Background(), Event{
		RunID: "r-2", TriggerKind: "manual", TriggeredBy: "uuid-1",
		Kind: "BILLING_COMMIT", Status: "FAILED",
		DurationMs:   "60000",
		ErrorMessage: "Constraint violation: contributions_period_unique",
	})

	if len(sender.Sent) != 1 {
		t.Fatalf("expected one sent message, got %d", len(sender.Sent))
	}
	msg := sender.Sent[0]
	if !strings.Contains(msg.Subject, "failed") {
		t.Errorf("subject should mention failed, got %q", msg.Subject)
	}
	if !strings.Contains(msg.HTMLBody, "Constraint violation") {
		t.Errorf("body should surface error message")
	}
}

func TestHumanDuration_picksAppropriateUnit(t *testing.T) {
	cases := map[time.Duration]string{
		500 * time.Millisecond: "500ms",
		45 * time.Second:       "45s",
		3 * time.Minute:        "3m0s",
		2 * time.Hour:          "2h0m",
	}
	for d, want := range cases {
		if got := humanDuration(d); got != want {
			t.Errorf("humanDuration(%v) = %q, want %q", d, got, want)
		}
	}
}
