package recipient

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

// ── Test doubles ─────────────────────────────────────────────────────────

// fakeRow satisfies pgx.Row with a canned set of column values. Errors
// short-circuit before Scan is invoked to mimic no-rows / driver errors.
type fakeRow struct {
	values []any
	err    error
}

func (r fakeRow) Scan(dest ...any) error {
	if r.err != nil {
		return r.err
	}
	if len(dest) != len(r.values) {
		return fmt.Errorf("fakeRow: expected %d dest, got %d", len(r.values), len(dest))
	}
	for i, v := range r.values {
		switch d := dest[i].(type) {
		case *string:
			if v == nil {
				return fmt.Errorf("fakeRow: dest[%d] is *string but value is nil — use **string for nullable columns", i)
			}
			*d = v.(string)
		case **string:
			if v == nil {
				*d = nil
			} else {
				s := v.(string)
				*d = &s
			}
		default:
			return fmt.Errorf("fakeRow: unsupported dest type %T at index %d", dest[i], i)
		}
	}
	return nil
}

// fakeDB routes each QueryRow to a canned response keyed by "which query"
// via a substring match against the SQL text. Test cases register the
// responses they expect; unmatched queries error, which surfaces missing
// wiring immediately.
type fakeDB struct {
	responses map[string]fakeRow // key = substring
	log       []string           // observed SQL for post-run assertions
}

func newFakeDB() *fakeDB {
	return &fakeDB{responses: map[string]fakeRow{}, log: nil}
}

func (f *fakeDB) on(match string, values ...any) *fakeDB {
	f.responses[match] = fakeRow{values: values}
	return f
}

func (f *fakeDB) onError(match string, err error) *fakeDB {
	f.responses[match] = fakeRow{err: err}
	return f
}

func (f *fakeDB) QueryRow(_ context.Context, sql string, _ ...any) pgx.Row {
	f.log = append(f.log, sql)
	for match, row := range f.responses {
		if strings.Contains(sql, match) {
			return row
		}
	}
	return fakeRow{err: fmt.Errorf("no stub for query: %s", sql)}
}

// ── Tests ────────────────────────────────────────────────────────────────

// Baseline: tenants + LIAISON kind resolves via group_liaisons.
func TestForGroup_liaisonKind_readsFromGroupLiaisons(t *testing.T) {
	db := newFakeDB().
		on("FROM public.tenants", "tenant_first_medfund").
		on("FROM tenant_first_medfund.groups",
			"11111111-1111-1111-1111-111111111111", "LIAISON", "billing@acme.test", "Acme Corp").
		on("FROM tenant_first_medfund.group_liaisons",
			"jane@acme.test", "Jane", "Doe")
	r := NewResolverWithDB(db)

	rcpt, err := r.ForGroup(context.Background(), "tenant-uuid", "group-uuid")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if rcpt.Email != "jane@acme.test" || rcpt.Kind != "GROUP_LIAISON" {
		t.Errorf("expected jane@acme.test / GROUP_LIAISON, got %+v", rcpt)
	}
	if rcpt.DisplayName != "Jane Doe" {
		t.Errorf("expected 'Jane Doe', got %q", rcpt.DisplayName)
	}
}

// MEMBER kind must read from tenant.members (not group_liaisons) — this was
// the third of the three silently-dropped paths before the ForGroup rewrite.
func TestForGroup_memberKind_readsFromMembers(t *testing.T) {
	db := newFakeDB().
		on("FROM public.tenants", "tenant_first_medfund").
		on("FROM tenant_first_medfund.groups",
			"22222222-2222-2222-2222-222222222222", "MEMBER", "fallback@acme.test", "Acme").
		on("FROM tenant_first_medfund.members",
			"mfemameckson@gmail.com", "Methuseli", "Mfema")
	r := NewResolverWithDB(db)

	rcpt, err := r.ForGroup(context.Background(), "tenant-uuid", "group-uuid")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if rcpt.Email != "mfemameckson@gmail.com" {
		t.Errorf("expected mfemameckson@gmail.com, got %q", rcpt.Email)
	}
	if rcpt.Kind != "GROUP_LIAISON" {
		t.Errorf("Kind must remain GROUP_LIAISON for template routing; got %q", rcpt.Kind)
	}
}

// STAFF kind must read from public.staff_users (unqualified — staff users
// are platform-wide, not tenant-scoped).
func TestForGroup_staffKind_readsFromPublicStaffUsers(t *testing.T) {
	db := newFakeDB().
		on("FROM public.tenants", "tenant_first_medfund").
		on("FROM tenant_first_medfund.groups",
			"33333333-3333-3333-3333-333333333333", "STAFF", "fallback@acme.test", "Acme").
		on("FROM public.staff_users",
			"ops@medfund.com", "Ops", "User")
	r := NewResolverWithDB(db)

	rcpt, err := r.ForGroup(context.Background(), "tenant-uuid", "group-uuid")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if rcpt.Email != "ops@medfund.com" {
		t.Errorf("expected ops@medfund.com, got %q", rcpt.Email)
	}
	if !containsAny(db.log, "public.staff_users") {
		t.Errorf("STAFF kind must query public.staff_users; queries: %v", db.log)
	}
}

// The v2 fallback: no liaison assigned but the group has an email → deliver
// to that address. This is the user-requested behaviour from 2026-07-02.
func TestForGroup_noLiaison_fallsBackToGroupEmail(t *testing.T) {
	db := newFakeDB().
		on("FROM public.tenants", "tenant_first_medfund").
		on("FROM tenant_first_medfund.groups",
			nil, nil, "billing@acme.test", "Acme Corp")
	r := NewResolverWithDB(db)

	rcpt, err := r.ForGroup(context.Background(), "tenant-uuid", "group-uuid")
	if err != nil {
		t.Fatalf("expected fallback to succeed, got error: %v", err)
	}
	if rcpt.Email != "billing@acme.test" {
		t.Errorf("expected billing@acme.test, got %q", rcpt.Email)
	}
	if rcpt.Kind != "GROUP" {
		t.Errorf("fallback recipient must be Kind=GROUP so the template can differentiate; got %q",
			rcpt.Kind)
	}
	if rcpt.DisplayName != "Acme Corp" {
		t.Errorf("DisplayName must fall back to the group name; got %q", rcpt.DisplayName)
	}
}

// Both liaison NULL AND email NULL → hard error (the caller publishes
// NotificationSent with FAILED). Silent drops here are what the outage
// looked like.
func TestForGroup_noLiaisonAndNoEmail_errorsCleanly(t *testing.T) {
	db := newFakeDB().
		on("FROM public.tenants", "tenant_first_medfund").
		on("FROM tenant_first_medfund.groups",
			nil, nil, nil, "Acme Corp")
	r := NewResolverWithDB(db)

	_, err := r.ForGroup(context.Background(), "tenant-uuid", "group-uuid")
	if err == nil {
		t.Fatalf("expected an error when both liaison and group email are unset")
	}
	if !strings.Contains(err.Error(), "no liaison") ||
		!strings.Contains(err.Error(), "no group email") {
		t.Errorf("error must explain both gaps for the operator; got: %v", err)
	}
}

// Unknown liaison_kind (schema drift) must error clearly rather than fall
// silently through the switch.
func TestForGroup_unknownLiaisonKind_errorsCleanly(t *testing.T) {
	db := newFakeDB().
		on("FROM public.tenants", "tenant_first_medfund").
		on("FROM tenant_first_medfund.groups",
			"44444444-4444-4444-4444-444444444444", "PARTNER", "fb@acme.test", "Acme")
	r := NewResolverWithDB(db)

	_, err := r.ForGroup(context.Background(), "tenant-uuid", "group-uuid")
	if err == nil || !strings.Contains(err.Error(), "unknown liaison_kind") {
		t.Errorf("expected unknown-kind error, got: %v", err)
	}
}

// Schema resolver refuses unsafe identifiers to guard the SQL interpolation
// path — same rule as TenantAwareConnectionFactory on the Java side.
func TestForGroup_unsafeSchemaName_refused(t *testing.T) {
	db := newFakeDB().
		on("FROM public.tenants", "tenant_first_medfund; DROP TABLE users")
	r := NewResolverWithDB(db)

	_, err := r.ForGroup(context.Background(), "tenant-uuid", "group-uuid")
	if err == nil || !strings.Contains(err.Error(), "unsafe schema identifier") {
		t.Errorf("expected unsafe-schema refusal, got: %v", err)
	}
}

// Tenant lookup failure surfaces upward — no silent fallback to public.
func TestForGroup_tenantLookupFails_propagates(t *testing.T) {
	db := newFakeDB().
		onError("FROM public.tenants", errors.New("connection reset"))
	r := NewResolverWithDB(db)

	_, err := r.ForGroup(context.Background(), "tenant-uuid", "group-uuid")
	if err == nil || !strings.Contains(err.Error(), "resolve schema") {
		t.Errorf("expected tenant lookup failure to bubble up, got: %v", err)
	}
}

// ── helpers ──────────────────────────────────────────────────────────────

func containsAny(haystack []string, needle string) bool {
	for _, h := range haystack {
		if strings.Contains(h, needle) {
			return true
		}
	}
	return false
}
