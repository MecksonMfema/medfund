package advice

import (
	"context"
	"errors"
	"fmt"
	"regexp"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// safeIdent guards the tenant schema identifier we interpolate into
// the SQL below — same defence-in-depth as recipient.Resolver.
var safeIdent = regexp.MustCompile(`^[a-zA-Z_][a-zA-Z0-9_]*$`)

type dbConn interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

// DBLookup implements AdviceLookup against a tenant's payment_advices
// row. Two-step: public.tenants → schema_name, then
// tenant_<slug>.payment_advices.
type DBLookup struct {
	Pool dbConn
}

func NewDBLookup(pool *pgxpool.Pool) *DBLookup { return &DBLookup{Pool: pool} }

func (l *DBLookup) Fetch(ctx context.Context, tenantID, adviceID string) (Totals, error) {
	if l == nil || l.Pool == nil {
		return Totals{}, errors.New("db lookup disabled")
	}
	var schema string
	if err := l.Pool.QueryRow(ctx,
		`SELECT schema_name FROM public.tenants WHERE id = $1`, tenantID).Scan(&schema); err != nil {
		return Totals{}, fmt.Errorf("resolve schema for tenant %s: %w", tenantID, err)
	}
	if !safeIdent.MatchString(schema) {
		return Totals{}, errors.New("rejecting unsafe schema identifier: " + schema)
	}
	q := fmt.Sprintf(`
		SELECT carried_in_amount::text, claims_paid_amount::text,
		       ctc_applied_amount::text, advance_applied_amount::text,
		       tax_withheld_amount::text, shortfall_amount::text,
		       net_due_amount::text
		  FROM %s.payment_advices
		 WHERE id = $1`, schema)
	var t Totals
	if err := l.Pool.QueryRow(ctx, q, adviceID).Scan(
		&t.CarriedIn, &t.ClaimsPaid, &t.CtcApplied, &t.AdvanceApplied,
		&t.TaxWithheld, &t.Shortfall, &t.NetDue); err != nil {
		return Totals{}, fmt.Errorf("fetch advice %s: %w", adviceID, err)
	}
	return t, nil
}
