package adapters

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestSarbAdapter_DevFallback(t *testing.T) {
	t.Setenv("SARB_FEED_URL", "")
	adapter := NewSarbAdapter()
	if adapter.Source() != "SARB_AUTO" {
		t.Fatalf("source=%q want SARB_AUTO", adapter.Source())
	}
	curve, err := adapter.Fetch(context.Background(), "ZAR")
	if err != nil {
		t.Fatalf("fetch: %v", err)
	}
	if curve.Currency != "ZAR" {
		t.Fatalf("currency=%q want ZAR", curve.Currency)
	}
	if len(curve.Points) == 0 {
		t.Fatal("expected non-empty dev-fallback points")
	}
}

func TestSarbAdapter_RejectsUnsupportedCurrency(t *testing.T) {
	adapter := NewSarbAdapter()
	_, err := adapter.Fetch(context.Background(), "GBP")
	if !errors.Is(err, ErrCurrencyNotSupported) {
		t.Fatalf("expected ErrCurrencyNotSupported got %v", err)
	}
}

func TestSarbAdapter_ParsesFeedResponse(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"currency": "ZAR",
			"effective_date": "2026-08-30",
			"points": [{"tenor_months": 60, "rate": "0.0900"}]
		}`))
	}))
	defer srv.Close()

	t.Setenv("SARB_FEED_URL", srv.URL)
	adapter := NewSarbAdapter()
	curve, err := adapter.Fetch(context.Background(), "ZAR")
	if err != nil {
		t.Fatalf("fetch: %v", err)
	}
	if len(curve.Points) != 1 {
		t.Fatalf("points=%d want 1", len(curve.Points))
	}
	if curve.Points[0].TenorMonths != 60 {
		t.Errorf("unexpected point[0]=%+v", curve.Points[0])
	}
}

func TestDecodeFeed_SkipsOutOfRangeTenors(t *testing.T) {
	body := `{"currency":"USD","effective_date":"2026-01-01","points":[
		{"tenor_months":0,"rate":"0.01"},
		{"tenor_months":700,"rate":"0.10"},
		{"tenor_months":12,"rate":"0.05"}
	]}`
	curve, err := decodeFeed("USD", stringReader(body))
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(curve.Points) != 1 || curve.Points[0].TenorMonths != 12 {
		t.Fatalf("expected only the in-range tenor to survive, got %+v", curve.Points)
	}
}

func TestDecodeFeed_RejectsEmpty(t *testing.T) {
	body := `{"currency":"USD","effective_date":"2026-01-01","points":[]}`
	if _, err := decodeFeed("USD", stringReader(body)); err == nil {
		t.Fatal("expected error for empty points slice")
	}
}
