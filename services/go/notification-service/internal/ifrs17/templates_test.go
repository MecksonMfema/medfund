package ifrs17

import (
	"strings"
	"testing"
	"time"
)

func TestTemplateFor_knownAndUnknownRoutes(t *testing.T) {
	cases := map[string]string{
		EventTypeOnerousTransition:        "templates/onerous_transition.html",
		EventTypeCsmNegative:              "templates/csm_negative.html",
		EventTypeLockedInCurveFallback:    "templates/locked_in_curve_fallback.html",
		EventTypeIbnrSubJobStale:          "templates/ibnr_sub_job_stale.html",
		EventTypeOpeningBalanceAutoDerive: "templates/opening_balance_auto_derived.html",
		"BRAND_NEW":                       "templates/generic.html",
		"":                                "templates/generic.html",
	}
	for eventType, want := range cases {
		if got := templateFor(eventType); got != want {
			t.Errorf("templateFor(%q) = %q, want %q", eventType, got, want)
		}
	}
}

func TestRenderBody_populatesFieldsForKnownEventType(t *testing.T) {
	e := Event{
		TenantID:    "tnt-1",
		CohortID:    "coh-42",
		PortfolioID: "prt-9",
		EventType:   EventTypeOnerousTransition,
		Severity:    "WARN",
		Message:     "auto-test flipped cohort ONEROUS",
		SourceRunID: "run-77",
		OccurredAt:  time.Now().UTC().Format(time.RFC3339),
	}
	body, err := renderBody(e)
	if err != nil {
		t.Fatalf("renderBody: %v", err)
	}
	for _, want := range []string{"coh-42", "prt-9", "WARN", "auto-test flipped cohort ONEROUS", "run-77", "ONEROUS_TRANSITION"} {
		if !strings.Contains(body, want) {
			t.Errorf("body missing %q: %s", want, body)
		}
	}
}

func TestRenderBody_omitsOptionalCohortWhenBlank(t *testing.T) {
	e := Event{
		TenantID:  "tnt-1",
		EventType: EventTypeCsmNegative,
		Severity:  "WARN",
		Message:   "CSM negative on portfolio-wide roll-forward",
	}
	body, err := renderBody(e)
	if err != nil {
		t.Fatalf("renderBody: %v", err)
	}
	// The template has an {{if .CohortID}} guard; blank cohort must
	// not render an empty <code></code> block.
	if strings.Contains(body, "<code></code>") {
		t.Errorf("empty CohortID leaked into body: %s", body)
	}
}

func TestSubjectFor_stableStringsPerType(t *testing.T) {
	if !strings.Contains(subjectFor(EventTypeOnerousTransition), "onerous") {
		t.Errorf("onerous subject missing: %q", subjectFor(EventTypeOnerousTransition))
	}
	if !strings.Contains(subjectFor("SOMETHING_ELSE"), "SOMETHING_ELSE") {
		t.Errorf("generic subject should include eventType: %q", subjectFor("SOMETHING_ELSE"))
	}
}

func TestEvent_ParsedOccurredAt_fallbackOnBadInput(t *testing.T) {
	e := Event{OccurredAt: "not-a-date"}
	if e.ParsedOccurredAt().IsZero() {
		t.Fatal("expected fallback to time.Now() on bad input")
	}
	e2 := Event{}
	if e2.ParsedOccurredAt().IsZero() {
		t.Fatal("expected fallback on empty input")
	}
	e3 := Event{OccurredAt: "2026-08-30T12:00:00Z"}
	got := e3.ParsedOccurredAt()
	if got.Year() != 2026 || got.Month() != 8 {
		t.Errorf("expected parsed 2026-08, got %s", got)
	}
}
