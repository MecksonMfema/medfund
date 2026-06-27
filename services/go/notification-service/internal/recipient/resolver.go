package recipient

import (
	"context"
	"errors"
	"fmt"
	"regexp"

	"github.com/jackc/pgx/v5/pgxpool"
)

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
	pool *pgxpool.Pool
}

func NewResolver(pool *pgxpool.Pool) *Resolver {
	return &Resolver{pool: pool}
}

// ForGroup returns the group's primary liaison email. The current
// model is one liaison per group via groups.liaison_user_id —
// {@code multi-liaison groups would need a join over a liaisons table
// keyed by group_id with a primary flag, which the schema doesn't yet
// have}.
func (r *Resolver) ForGroup(ctx context.Context, tenantID, groupID string) (Recipient, error) {
	schema, err := r.lookupSchema(ctx, tenantID)
	if err != nil {
		return Recipient{}, err
	}
	q := fmt.Sprintf(`
		SELECT gl.email, gl.first_name, gl.last_name
		  FROM %[1]s.groups g
		  JOIN %[1]s.group_liaisons gl ON gl.id = g.liaison_user_id
		 WHERE g.id = $1`, schema)
	var email, first, last string
	if err := r.pool.QueryRow(ctx, q, groupID).Scan(&email, &first, &last); err != nil {
		return Recipient{}, fmt.Errorf("lookup group liaison %s: %w", groupID, err)
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
