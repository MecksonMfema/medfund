// Package adapters holds the per-jurisdiction yield-curve fetchers. Each
// adapter turns a currency + reference date into a Curve — a series of
// tenor/spot-rate points ready for the publisher to hand off to
// tenancy-service.
//
// New jurisdictions (Fed / BoE / CBK / …) drop in as sibling files
// implementing JurisdictionAdapter; register them in For() below.
package adapters

import (
	"context"
	"errors"
	"time"
)

// Point is one (tenor, spot rate) pair.
type Point struct {
	TenorMonths int    `json:"tenor_months"`
	SpotRate    string `json:"spot_rate"` // decimal string to preserve precision
}

// Curve is the full set of tenor points for one currency at one date.
type Curve struct {
	Currency      string    `json:"currency"`
	EffectiveFrom time.Time `json:"effective_from"`
	Points        []Point   `json:"points"`
}

// JurisdictionAdapter fetches a single-currency yield curve from a
// central bank / market-data source. Implementations are stateless
// (fresh HTTP client per call is fine at daily cadence) and always
// honour the passed ctx for cancel + timeout.
type JurisdictionAdapter interface {
	// Source returns the CHECK-constraint value the caller records
	// in tenant_yield_curve.source and the wire event.
	Source() string
	// Fetch returns the curve for the given currency. Adapters may
	// return ErrCurrencyNotSupported if the source doesn't publish
	// rates for the requested currency (RBZ doesn't publish ZAR
	// rates for example).
	Fetch(ctx context.Context, currency string) (Curve, error)
}

// ErrCurrencyNotSupported flags a mismatch between the requested
// currency and the adapter's coverage. Callers treat this as a
// skip + warn — not a retryable failure.
var ErrCurrencyNotSupported = errors.New("currency not supported by this jurisdiction adapter")

// For returns the adapter matching the tenancy-service source string,
// or nil if unknown. The daemon logs + skips unknown sources rather
// than crashing so a rogue V167 CHECK addition doesn't take the
// scheduler down.
func For(source string) JurisdictionAdapter {
	switch source {
	case "RBZ_AUTO":
		return NewRbzAdapter()
	case "SARB_AUTO":
		return NewSarbAdapter()
	default:
		return nil
	}
}
