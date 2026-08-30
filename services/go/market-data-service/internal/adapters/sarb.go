package adapters

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

// SarbAdapter mirrors RbzAdapter for the South African Reserve Bank
// feed. Same internal-proxy shape (SARB_FEED_URL env). Covers ZAR and
// USD (SARB publishes USD swap rates alongside its own currency).
type SarbAdapter struct {
	FeedURL string
	Client  *http.Client
}

// NewSarbAdapter reads SARB_FEED_URL. Empty ⇒ dev sample fallback.
func NewSarbAdapter() *SarbAdapter {
	return &SarbAdapter{
		FeedURL: os.Getenv("SARB_FEED_URL"),
		Client:  &http.Client{Timeout: 10 * time.Second},
	}
}

// Source is the CHECK value written to tenant_yield_curve.source.
func (a *SarbAdapter) Source() string { return "SARB_AUTO" }

// Fetch returns the SARB curve for the given currency (ZAR or USD).
func (a *SarbAdapter) Fetch(ctx context.Context, currency string) (Curve, error) {
	upper := strings.ToUpper(currency)
	if upper != "ZAR" && upper != "USD" {
		return Curve{}, ErrCurrencyNotSupported
	}
	if a.FeedURL == "" {
		return sarbDevSample(upper), nil
	}
	url := fmt.Sprintf("%s?currency=%s", strings.TrimRight(a.FeedURL, "/"), upper)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return Curve{}, fmt.Errorf("build sarb request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	resp, err := a.Client.Do(req)
	if err != nil {
		return Curve{}, fmt.Errorf("call sarb feed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return Curve{}, fmt.Errorf("sarb feed returned %d: %s", resp.StatusCode, string(body))
	}
	return decodeFeed(upper, resp.Body)
}

// sarbDevSample is only used when SARB_FEED_URL is unset — mirrors the
// RBZ dev fallback so dev output looks consistent across adapters.
func sarbDevSample(currency string) Curve {
	// SARB curves in dev are shaped slightly differently from the RBZ
	// dev sample so a smoke test can tell them apart in kafka-ui.
	return Curve{
		Currency:      currency,
		EffectiveFrom: time.Now().UTC().Truncate(24 * time.Hour),
		Points: []Point{
			{TenorMonths: 3, SpotRate: "0.0725000"},
			{TenorMonths: 12, SpotRate: "0.0800000"},
			{TenorMonths: 36, SpotRate: "0.0850000"},
			{TenorMonths: 60, SpotRate: "0.0900000"},
			{TenorMonths: 120, SpotRate: "0.0950000"},
			{TenorMonths: 240, SpotRate: "0.1000000"},
		},
	}
}
