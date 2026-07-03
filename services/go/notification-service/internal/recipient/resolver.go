package recipient

import (
	"context"
	"errors"
	"fmt"
	"regexp"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// dbConn is the tiny slice of pgxpool.Pool the resolver actually uses. It
// exists so tests can inject a fake without spinning a testcontainer — the
// production path still hands in a *pgxpool.Pool which satisfies this
// interface implicitly.
type dbConn interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

// safeIdent gates the schema name we interpolate into SQL — same regex
// as TenantAwareConnectionFactory's defence in depth. Anything that
// doesn't match a plain SQL identifier is refused before it touches
// the statement.
var safeIdent = regexp.MustCompile(`^[a-zA-Z_][a-zA-Z0-9_]*$`)

// Recipient is the fully-resolved target for an invoice email. Always
// has Email + DisplayName populated; Kind tells the template which
// salutation to use.
type Recipient struct {
	Email       string
	DisplayName string
	Kind        string // "GROUP_LIAISON" or "MEMBER"
}

// Resolver looks up the recipient details for an invoice from the
// tenant's database schema. Two-step: public.tenants → schema_name,
// then tenant_<slug>.{group_liaisons | members}.
type Resolver struct {
	pool dbConn
}

func NewResolver(pool *pgxpool.Pool) *Resolver {
	return &Resolver{pool: pool}
}

// NewResolverWithDB is the test seam — production code should always use
// NewResolver so the return type is bounded to the real pool.
func NewResolverWithDB(db dbConn) *Resolver {
	return &Resolver{pool: db}
}

// ForGroup returns the group's primary liaison email. Groups pin their
// liaison via (liaison_user_id, liaison_kind) — the referenced row lives
// in one of three tables:
//
//   - LIAISON → tenant.group_liaisons  (dedicated liaison user)
//   - MEMBER  → tenant.members          (a member of the group acts as liaison)
//   - STAFF   → public.staff_users      (a staff user acts as liaison)
//
// A CHECK constraint on groups (groups_liaison_pair_consistent) enforces
// that both columns are either NULL together or NOT NULL together — so
// missing pin ⇒ no email path; wrong-table lookup ⇒ silent drop. The
// original resolver only knew about LIAISON and dropped MEMBER / STAFF
// groups without an emitted error, which is what stalled every invoice
// email in the 2026-07-02 outage.
func (r *Resolver) ForGroup(ctx context.Context, tenantID, groupID string) (Recipient, error) {
	schema, err := r.lookupSchema(ctx, tenantID)
	if err != nil {
		return Recipient{}, err
	}

	var liaisonUserID, liaisonKind, groupEmail, groupName *string
	if err := r.pool.QueryRow(ctx, fmt.Sprintf(
		`SELECT liaison_user_id::text, liaison_kind, email, name FROM %s.groups WHERE id = $1`, schema),
		groupID).Scan(&liaisonUserID, &liaisonKind, &groupEmail, &groupName); err != nil {
		return Recipient{}, fmt.Errorf("lookup group %s: %w", groupID, err)
	}
	// Fallback path: no liaison assigned → deliver to the group's own email.
	// Enforced on group creation (@NotBlank on CreateGroupRequest.email), so
	// only pre-V038 rows can hit this branch without an email — those still
	// error cleanly here rather than silently dropping the statement.
	if liaisonUserID == nil || liaisonKind == nil {
		if groupEmail != nil && *groupEmail != "" {
			display := ""
			if groupName != nil {
				display = *groupName
			}
			return Recipient{
				Email:       *groupEmail,
				DisplayName: display,
				Kind:        "GROUP",
			}, nil
		}
		return Recipient{}, fmt.Errorf("group %s has no liaison assigned "+
			"and no group email on file — nothing to route to", groupID)
	}

	var email, first, last string
	var q string
	switch *liaisonKind {
	case "LIAISON":
		q = fmt.Sprintf(
			`SELECT email, first_name, last_name FROM %s.group_liaisons WHERE id = $1`, schema)
	case "MEMBER":
		q = fmt.Sprintf(
			`SELECT email, first_name, last_name FROM %s.members WHERE id = $1`, schema)
	case "STAFF":
		q = `SELECT email, first_name, last_name FROM public.staff_users WHERE id = $1`
	default:
		return Recipient{}, fmt.Errorf("group %s has unknown liaison_kind %q", groupID, *liaisonKind)
	}
	if err := r.pool.QueryRow(ctx, q, *liaisonUserID).Scan(&email, &first, &last); err != nil {
		return Recipient{}, fmt.Errorf("lookup %s liaison for group %s: %w",
			*liaisonKind, groupID, err)
	}
	if email == "" {
		return Recipient{}, fmt.Errorf("group %s liaison (%s) has no email on file",
			groupID, *liaisonKind)
	}
	return Recipient{
		Email:       email,
		DisplayName: fmt.Sprintf("%s %s", first, last),
		Kind:        "GROUP_LIAISON",
	}, nil
}

// ForMember returns the member's own email + display name.
func (r *Resolver) ForMember(ctx context.Context, tenantID, memberID string) (Recipient, error) {
	schema, err := r.lookupSchema(ctx, tenantID)
	if err != nil {
		return Recipient{}, err
	}
	q := fmt.Sprintf(`
		SELECT email, first_name, last_name
		  FROM %s.members
		 WHERE id = $1`, schema)
	var email, first, last string
	if err := r.pool.QueryRow(ctx, q, memberID).Scan(&email, &first, &last); err != nil {
		return Recipient{}, fmt.Errorf("lookup member %s: %w", memberID, err)
	}
	if email == "" {
		return Recipient{}, fmt.Errorf("member %s has no email on file", memberID)
	}
	return Recipient{
		Email:       email,
		DisplayName: fmt.Sprintf("%s %s", first, last),
		Kind:        "MEMBER",
	}, nil
}

func (r *Resolver) lookupSchema(ctx context.Context, tenantID string) (string, error) {
	var schema string
	err := r.pool.QueryRow(ctx,
		`SELECT schema_name FROM public.tenants WHERE id = $1`, tenantID).Scan(&schema)
	if err != nil {
		return "", fmt.Errorf("resolve schema for tenant %s: %w", tenantID, err)
	}
	if !safeIdent.MatchString(schema) {
		return "", errors.New("rejecting unsafe schema identifier: " + schema)
	}
	return schema, nil
}
