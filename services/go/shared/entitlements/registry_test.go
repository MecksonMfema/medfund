package entitlements

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

const (
	tenantA = "11111111-1111-1111-1111-111111111111"
	tenantB = "22222222-2222-2222-2222-222222222222"
)

func TestRegistry_ReadsFalseBeforeBootstrap(t *testing.T) {
	// The safe default matters: a gateway that cannot reach tenancy-service
	// must behave as though every unsold module is off, never on (G3).
	r := New("http://unused.invalid")
	if r.IsEntitled(tenantA, "KYC") {
		t.Fatal("expected an un-bootstrapped registry to read as not-entitled")
	}
}

func TestRegistry_Bootstrap(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		gotPath = req.URL.Path
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`[{"tenantId":"` + tenantA + `","moduleKey":"KYC","enabled":true},` +
			`{"tenantId":"` + tenantB + `","moduleKey":"KYC","enabled":false}]`))
	}))
	defer srv.Close()

	r := New(srv.URL)
	if err := r.Bootstrap(context.Background()); err != nil {
		t.Fatalf("bootstrap: %v", err)
	}

	if gotPath != bootstrapPath {
		t.Fatalf("expected %s, got %s", bootstrapPath, gotPath)
	}
	if !r.IsEntitled(tenantA, "KYC") {
		t.Fatal("tenantA should be entitled to KYC")
	}
	if r.IsEntitled(tenantB, "KYC") {
		t.Fatal("tenantB should NOT be entitled to KYC")
	}
	// Entitlement is per-tenant: tenantB's disabled row must not leak to tenantA,
	// and an unknown module reads false.
	if r.IsEntitled(tenantA, "KYC_BIOMETRICS") {
		t.Fatal("unknown module must read as not-entitled")
	}
}

func TestRegistry_BootstrapTrimsTrailingSlash(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		gotPath = req.URL.Path
		w.Write([]byte(`[]`))
	}))
	defer srv.Close()

	r := New(srv.URL + "/")
	if err := r.Bootstrap(context.Background()); err != nil {
		t.Fatalf("bootstrap: %v", err)
	}
	if gotPath != bootstrapPath {
		t.Fatalf("expected %s, got %s", bootstrapPath, gotPath)
	}
}

func TestRegistry_BootstrapErrorsOnNon200(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	r := New(srv.URL)
	if err := r.Bootstrap(context.Background()); err == nil {
		t.Fatal("expected an error for a 503 bootstrap")
	}
	if r.IsEntitled(tenantA, "KYC") {
		t.Fatal("a failed bootstrap must leave every module not-entitled")
	}
}

func TestRegistry_ApplyEvent(t *testing.T) {
	r := New("http://unused.invalid")

	r.apply([]byte(`{"tenantId":"` + tenantA + `","moduleKey":"KYC","enabled":true,` +
		`"updatedAt":"2026-10-23T12:00:00Z","actor":"admin@medfund.com"}`))
	if !r.IsEntitled(tenantA, "KYC") {
		t.Fatal("event should have entitled tenantA to KYC")
	}

	r.apply([]byte(`{"tenantId":"` + tenantA + `","moduleKey":"KYC","enabled":false}`))
	if r.IsEntitled(tenantA, "KYC") {
		t.Fatal("event should have revoked tenantA's KYC entitlement")
	}
}

func TestRegistry_ApplyIgnoresBadPayloads(t *testing.T) {
	r := New("http://unused.invalid")
	r.apply([]byte(`{"tenantId":"` + tenantA + `","moduleKey":"KYC","enabled":true}`))

	r.apply([]byte(`not json`))
	r.apply([]byte(`{"moduleKey":"KYC","enabled":true}`)) // no tenant
	r.apply([]byte(`{"tenantId":"` + tenantB + `","enabled":true}`)) // no module

	if !r.IsEntitled(tenantA, "KYC") {
		t.Fatal("bad payloads must leave existing state untouched")
	}
	if len(r.Snapshot()) != 1 {
		t.Fatalf("expected exactly one entitlement, got %v", r.Snapshot())
	}
}

func TestRegistry_SnapshotIsACopy(t *testing.T) {
	r := New("http://unused.invalid")
	r.apply([]byte(`{"tenantId":"` + tenantA + `","moduleKey":"KYC","enabled":true}`))

	snap := r.Snapshot()
	snap[key(tenantA, "KYC")] = false

	if !r.IsEntitled(tenantA, "KYC") {
		t.Fatal("mutating the snapshot must not affect the registry")
	}
}
