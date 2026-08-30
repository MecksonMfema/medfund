package ifrs17

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHTTPTenancyClient_ActiveFor_returnsRows(t *testing.T) {
	stubRows := []NotificationConfig{
		{ID: "row-1", TenantID: "tnt-1", EventType: "ONEROUS_TRANSITION",
			DeliveryMethod: "EMAIL", Recipient: "alice@acme.test",
			ThrottleMinutes: 30, IsActive: true},
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if !strings.HasSuffix(r.URL.Path, "/api/v1/tenants/tnt-1/ifrs17-notification-config/active") {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		if r.URL.Query().Get("eventType") != "ONEROUS_TRANSITION" {
			t.Errorf("wrong eventType: %s", r.URL.Query().Get("eventType"))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(stubRows)
	}))
	defer srv.Close()

	c := NewHTTPTenancyClient(srv.URL)
	got, err := c.ActiveFor(context.Background(), "tnt-1", "ONEROUS_TRANSITION")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(got) != 1 || got[0].Recipient != "alice@acme.test" {
		t.Fatalf("unexpected rows: %+v", got)
	}
}

func TestHTTPTenancyClient_ActiveFor_nonOkReturnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "boom", http.StatusInternalServerError)
	}))
	defer srv.Close()

	c := NewHTTPTenancyClient(srv.URL)
	_, err := c.ActiveFor(context.Background(), "tnt-1", "CSM_NEGATIVE")
	if err == nil {
		t.Fatal("expected error on 500 response")
	}
	if !strings.Contains(err.Error(), "500") {
		t.Errorf("expected status code in error, got %v", err)
	}
}

func TestHTTPTenancyClient_ActiveFor_unconfiguredIsError(t *testing.T) {
	var c *HTTPTenancyClient
	_, err := c.ActiveFor(context.Background(), "tnt-1", "ONEROUS_TRANSITION")
	if err == nil {
		t.Fatal("expected error when client is nil")
	}
}
