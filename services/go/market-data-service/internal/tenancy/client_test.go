package tenancy

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHTTPClient_ListEnabledParsesRows(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/internal/v1/market-data-config/enabled") {
			t.Errorf("unexpected path=%s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[
			{"id":"a","tenantId":"t1","currency":"USD","source":"RBZ_AUTO","autoFetchEnabled":true},
			{"id":"b","tenantId":"t2","currency":"ZAR","source":"SARB_AUTO","autoFetchEnabled":true}
		]`))
	}))
	defer srv.Close()

	c := NewHTTPClient(srv.URL)
	rows, err := c.ListEnabled(context.Background())
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(rows) != 2 {
		t.Fatalf("rows=%d want 2", len(rows))
	}
	if rows[0].TenantID != "t1" || rows[0].Source != "RBZ_AUTO" {
		t.Errorf("row[0]=%+v", rows[0])
	}
}

func TestHTTPClient_NilBaseURLReturnsError(t *testing.T) {
	c := NewHTTPClient("")
	if _, err := c.ListEnabled(context.Background()); err == nil {
		t.Fatal("expected error on empty baseURL")
	}
}

func TestHTTPClient_Non200ReturnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	c := NewHTTPClient(srv.URL)
	if _, err := c.ListEnabled(context.Background()); err == nil {
		t.Fatal("expected error on 500 response")
	}
}
