package platform

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/medfund/gateway/internal/config"
)

// ─── buildCountSeries / buildMoneySumSeries ──────────────────────────────────

func TestBuildCountSeries_countsIntoMonthlyBucketsForYearPeriod(t *testing.T) {
	now := time.Now().UTC()
	rows := []analyticsRow{
		{Ts: time.Date(now.Year(), 1, 15, 12, 0, 0, 0, time.UTC).Format(time.RFC3339)},
		{Ts: time.Date(now.Year(), 1, 20, 12, 0, 0, 0, time.UTC).Format(time.RFC3339)},
		{Ts: time.Date(now.Year(), 3, 5, 12, 0, 0, 0, time.UTC).Format(time.RFC3339)},
	}
	out := buildCountSeries("year", rows)
	if len(out) != 12 {
		t.Fatalf("expected 12 monthly buckets, got %d", len(out))
	}
	// Only rows within the past year contribute — in-year rows are inside the cutoff.
	if out[0].Value != 2 {
		t.Errorf("Jan bucket = %v, want 2", out[0].Value)
	}
	if out[2].Value != 1 {
		t.Errorf("Mar bucket = %v, want 1", out[2].Value)
	}
}

func TestBuildCountSeries_ignoresOldRowsBeforeCutoff(t *testing.T) {
	// 2 years old — before the "year" cutoff.
	old := time.Now().UTC().AddDate(-2, 0, 0).Format(time.RFC3339)
	out := buildCountSeries("year", []analyticsRow{{Ts: old}})
	for i, b := range out {
		if b.Value != 0 {
			t.Errorf("bucket %d should be zero (old row), got %v", i, b.Value)
		}
	}
}

func TestBuildCountSeries_ignoresUnparseableTimestamps(t *testing.T) {
	out := buildCountSeries("year", []analyticsRow{{Ts: "not-a-date"}})
	for i, b := range out {
		if b.Value != 0 {
			t.Errorf("bucket %d should be zero, got %v", i, b.Value)
		}
	}
}

func TestBuildMoneySumSeries_sumsFloatValuesPerBucket(t *testing.T) {
	now := time.Now().UTC()
	rows := []analyticsRow{
		{Ts: time.Date(now.Year(), 1, 5, 0, 0, 0, 0, time.UTC).Format(time.RFC3339), Value: 100.50},
		{Ts: time.Date(now.Year(), 1, 25, 0, 0, 0, 0, time.UTC).Format(time.RFC3339), Value: 249.50},
	}
	out := buildMoneySumSeries("year", rows)
	if len(out) != 12 {
		t.Fatalf("expected 12 buckets, got %d", len(out))
	}
	if out[0].Value != 350.0 {
		t.Errorf("Jan sum = %v, want 350.0", out[0].Value)
	}
}

// ─── periodQueryString ───────────────────────────────────────────────────────

func TestPeriodQueryString_weekMonthYearProducesRange(t *testing.T) {
	for _, p := range []string{"week", "month", "year"} {
		q := periodQueryString(p)
		if q == "" {
			t.Errorf("period %q returned empty query", p)
		}
		if q[:1] != "?" {
			t.Errorf("period %q missing leading ?: %q", p, q)
		}
	}
}

func TestPeriodQueryString_allReturnsEmpty(t *testing.T) {
	if got := periodQueryString("all"); got != "" {
		t.Errorf("all period should send no bounds, got %q", got)
	}
	if got := periodQueryString("bogus"); got != "" {
		t.Errorf("unknown period should default to no bounds, got %q", got)
	}
}

// ─── Handler wiring via httptest upstreams ───────────────────────────────────

// upstream stands in for a Java service — returns a canned JSON body for any
// path matching the given prefix. Used to exercise the handler code paths
// without a real service listening.
func upstream(t *testing.T, body interface{}) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(body)
	}))
}

func newHandlerWithUpstreams(cfg *config.Config) (*fiber.App, *Handler) {
	h := NewHandler(cfg)
	app := fiber.New()
	h.Register(app.Group("/api/v1/platform"))
	return app, h
}

func TestGetTenantGrowth_pullsRawRowsFromTenancyService(t *testing.T) {
	tenancy := upstream(t, []map[string]string{
		{"ts": time.Now().UTC().AddDate(0, -1, 0).Format(time.RFC3339)},
	})
	defer tenancy.Close()

	cfg := &config.Config{TenancyServiceURL: tenancy.URL}
	app, _ := newHandlerWithUpstreams(cfg)

	resp, err := app.Test(httptest.NewRequest("GET", "/api/v1/platform/analytics/tenant-growth?period=year", nil))
	if err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if resp.StatusCode != fiber.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	var out []seriesPoint
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(out) != 12 {
		t.Errorf("year period should produce 12 buckets, got %d", len(out))
	}
	// One of the 12 buckets should hold the single row we seeded.
	var total float64
	for _, s := range out {
		total += s.Value
	}
	if total != 1 {
		t.Errorf("total across buckets = %v, want 1", total)
	}
}

func TestGetBillingOverTime_unwrapsAnalyticsEnvelopeAndBuckets(t *testing.T) {
	now := time.Now().UTC()
	contrib := upstream(t, analyticsEnvelope{
		Rows: []analyticsRow{
			{Ts: time.Date(now.Year(), now.Month(), 5, 0, 0, 0, 0, time.UTC).Format(time.RFC3339), Value: 1234.56},
		},
		Skipped: 2,
	})
	defer contrib.Close()

	cfg := &config.Config{ContribServiceURL: contrib.URL}
	app, _ := newHandlerWithUpstreams(cfg)

	resp, err := app.Test(httptest.NewRequest("GET", "/api/v1/platform/analytics/billing-over-time?period=year", nil))
	if err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if resp.StatusCode != fiber.StatusOK {
		t.Fatalf("status = %d", resp.StatusCode)
	}
	var out []seriesPoint
	_ = json.NewDecoder(resp.Body).Decode(&out)

	var total float64
	for _, s := range out {
		total += s.Value
	}
	if total != 1234.56 {
		t.Errorf("total sum = %v, want 1234.56 (skipped counter is log-only)", total)
	}
}

func TestGetRevenueByTenant_forwardsTop10AsNameValueRows(t *testing.T) {
	contrib := upstream(t, tenantRevenueEnvelope{
		Rows: []tenantRevenueRow{
			{TenantName: "Alpha Health", Value: 50000},
			{TenantName: "Beta Cover", Value: 25000},
		},
		Skipped: 0,
	})
	defer contrib.Close()

	cfg := &config.Config{ContribServiceURL: contrib.URL}
	app, _ := newHandlerWithUpstreams(cfg)

	resp, err := app.Test(httptest.NewRequest("GET", "/api/v1/platform/analytics/revenue-by-tenant?period=year", nil))
	if err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if resp.StatusCode != fiber.StatusOK {
		t.Fatalf("status = %d", resp.StatusCode)
	}
	var out []map[string]interface{}
	_ = json.NewDecoder(resp.Body).Decode(&out)
	if len(out) != 2 {
		t.Fatalf("expected 2 rows, got %d", len(out))
	}
	if out[0]["name"] != "Alpha Health" {
		t.Errorf("first name = %v, want Alpha Health", out[0]["name"])
	}
	if out[0]["value"].(float64) != 50000 {
		t.Errorf("first value = %v, want 50000", out[0]["value"])
	}
}

func TestGetClaimsOverTime_unwrapsPlainRowArray(t *testing.T) {
	now := time.Now().UTC()
	claims := upstream(t, []analyticsRow{
		{Ts: time.Date(now.Year(), now.Month(), 10, 0, 0, 0, 0, time.UTC).Format(time.RFC3339)},
		{Ts: time.Date(now.Year(), now.Month(), 11, 0, 0, 0, 0, time.UTC).Format(time.RFC3339)},
	})
	defer claims.Close()

	cfg := &config.Config{ClaimsServiceURL: claims.URL}
	app, _ := newHandlerWithUpstreams(cfg)

	resp, err := app.Test(httptest.NewRequest("GET", "/api/v1/platform/analytics/claims-over-time?period=year", nil))
	if err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if resp.StatusCode != fiber.StatusOK {
		t.Fatalf("status = %d", resp.StatusCode)
	}
	var out []seriesPoint
	_ = json.NewDecoder(resp.Body).Decode(&out)
	var total float64
	for _, s := range out {
		total += s.Value
	}
	if total != 2 {
		t.Errorf("total count = %v, want 2", total)
	}
}
