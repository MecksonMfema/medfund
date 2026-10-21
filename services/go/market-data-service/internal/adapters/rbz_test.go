package adapters

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRbzAdapter_DevFallback_WhenFeedURLEmpty(t *testing.T) {
	// Guard against a stray env leaking into the test.
	t.Setenv("RBZ_FEED_URL", "")
	adapter := NewRbzAdapter()
	if adapter.Source() != "RBZ_AUTO" {
		t.Fatalf("source=%q want RBZ_AUTO", adapter.Source())
	}
	curve, err := adapter.Fetch(context.Background(), "USD")
	if err != nil {
		t.Fatalf("dev fallback fetch failed: %v", err)
	}
	if curve.Currency != "USD" {
		t.Fatalf("currency=%q want USD", curve.Currency)
	}
	if len(curve.Points) == 0 {
		t.Fatal("expected non-empty dev-fallback points")
	}
}

func TestRbzAdapter_RejectsUnsupportedCurrency(t *testing.T) {
	adapter := NewRbzAdapter()
	_, err := adapter.Fetch(context.Background(), "EUR")
	if !errors.Is(err, ErrCurrencyNotSupported) {
		t.Fatalf("expected ErrCurrencyNotSupported got %v", err)
	}
}

func TestRbzAdapter_ParsesFeedResponse(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.URL.Query().Get("currency"); got != "USD" {
			t.Errorf("query currency=%q want USD", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"currency": "USD",
			"effective_date": "2026-08-30",
			"points": [
				{"tenor_months": 12, "rate": "0.0500"},
				{"tenor_months": 24, "rate": "0.0525"}
			]
		}`))
	}))
	defer srv.Close()

	t.Setenv("RBZ_FEED_URL", srv.URL)
	adapter := NewRbzAdapter()
	curve, err := adapter.Fetch(context.Background(), "USD")
	if err != nil {
		t.Fatalf("fetch: %v", err)
	}
	if len(curve.Points) != 2 {
		t.Fatalf("points=%d want 2", len(curve.Points))
	}
	if curve.Points[0].TenorMonths != 12 || curve.Points[0].SpotRate != "0.0500" {
		t.Errorf("unexpected point[0]=%+v", curve.Points[0])
	}
}

func TestRbzAdapter_ErrorOnNon200(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	t.Setenv("RBZ_FEED_URL", srv.URL)
	adapter := NewRbzAdapter()
	if _, err := adapter.Fetch(context.Background(), "USD"); err == nil {
		t.Fatal("expected non-nil error for 503 response")
	}
}

func TestFor_UnknownSourceReturnsNil(t *testing.T) {
	if got := For("XYZ_AUTO"); got != nil {
		t.Fatalf("For(XYZ_AUTO)=%T want nil", got)
	}
}

func TestFor_KnownSourcesReturnAdapters(t *testing.T) {
	if got := For("RBZ_AUTO"); got == nil {
		t.Fatal("For(RBZ_AUTO) returned nil")
	}
	if got := For("SARB_AUTO"); got == nil {
		t.Fatal("For(SARB_AUTO) returned nil")
	}
}
