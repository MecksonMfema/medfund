package adapters

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

// RbzAdapter fetches yield curve points from the Reserve Bank of
// Zimbabwe's published rates. RBZ does not currently expose a stable
// public JSON API, so the adapter targets a small internal proxy the
// InsureFlow ops team maintains (env RBZ_FEED_URL). Response schema:
//
//	{
//	  "currency": "USD",
//	  "effective_date": "2026-08-30",
//	  "points": [
//	    {"tenor_months": 3,  "rate": 0.0450},
//	    {"tenor_months": 12, "rate": 0.0500},
//	    ...
//	  ]
//	}
//
// If RBZ_FEED_URL is unset the adapter falls back to a fixed sample
// curve so a `make market-data-service` in dev produces sensible rows
// without an external dependency.
type RbzAdapter struct {
	FeedURL string
	Client  *http.Client
}

// NewRbzAdapter reads RBZ_FEED_URL at construction time. Empty ⇒ the
// dev sample-curve fallback fires.
func NewRbzAdapter() *RbzAdapter {
	return &RbzAdapter{
		FeedURL: os.Getenv("RBZ_FEED_URL"),
		Client:  &http.Client{Timeout: 10 * time.Second},
	}
}

// Source is the CHECK value written to tenant_yield_curve.source.
func (a *RbzAdapter) Source() string { return "RBZ_AUTO" }

// Fetch returns the RBZ curve for the given currency. RBZ publishes
// primarily for USD and ZWL; other currencies return ErrCurrencyNotSupported.
func (a *RbzAdapter) Fetch(ctx context.Context, currency string) (Curve, error) {
	upper := strings.ToUpper(currency)
	if upper != "USD" && upper != "ZWL" {
		return Curve{}, ErrCurrencyNotSupported
	}
	if a.FeedURL == "" {
		return devSampleCurve(upper), nil
	}
	url := fmt.Sprintf("%s?currency=%s", strings.TrimRight(a.FeedURL, "/"), upper)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return Curve{}, fmt.Errorf("build rbz request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	resp, err := a.Client.Do(req)
	if err != nil {
		return Curve{}, fmt.Errorf("call rbz feed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return Curve{}, fmt.Errorf("rbz feed returned %d: %s", resp.StatusCode, string(body))
	}
	return decodeFeed(upper, resp.Body)
}

// devSampleCurve is only used when RBZ_FEED_URL is unset — keeps
// `make market-data-service` producing rows in dev even offline.
func devSampleCurve(currency string) Curve {
	return Curve{
		Currency:      currency,
		EffectiveFrom: time.Now().UTC().Truncate(24 * time.Hour),
		Points: []Point{
			{TenorMonths: 3, SpotRate: "0.0450000"},
			{TenorMonths: 12, SpotRate: "0.0500000"},
			{TenorMonths: 24, SpotRate: "0.0530000"},
			{TenorMonths: 60, SpotRate: "0.0575000"},
			{TenorMonths: 120, SpotRate: "0.0600000"},
		},
	}
}

// decodeFeed is shared between adapters — feed shape is identical
// across the internal RBZ/SARB proxies.
func decodeFeed(currency string, body io.Reader) (Curve, error) {
	var raw struct {
		Currency      string `json:"currency"`
		EffectiveDate string `json:"effective_date"`
		Points        []struct {
			TenorMonths int    `json:"tenor_months"`
			Rate        string `json:"rate"`
		} `json:"points"`
	}
	if err := json.NewDecoder(body).Decode(&raw); err != nil {
		return Curve{}, fmt.Errorf("decode feed: %w", err)
	}
	eff, err := time.Parse("2006-01-02", raw.EffectiveDate)
	if err != nil {
		return Curve{}, fmt.Errorf("parse effective_date %q: %w", raw.EffectiveDate, err)
	}
	out := Curve{Currency: currency, EffectiveFrom: eff}
	for _, p := range raw.Points {
		if p.TenorMonths < 1 || p.TenorMonths > 600 {
			continue
		}
		out.Points = append(out.Points, Point{
			TenorMonths: p.TenorMonths,
			SpotRate:    p.Rate,
		})
	}
	if len(out.Points) == 0 {
		return Curve{}, fmt.Errorf("feed for %s returned no valid points", currency)
	}
	return out, nil
}
