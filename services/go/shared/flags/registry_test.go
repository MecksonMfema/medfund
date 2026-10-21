package flags

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRegistry_ReadsFalseBeforeBootstrap(t *testing.T) {
	// The safe default matters: a service that cannot reach tenancy-service
	// must behave as though every unlaunched feature is off, never on.
	r := New("http://unused.invalid")
	if r.IsEnabled("AI_ADJUDICATION") {
		t.Fatal("expected an un-bootstrapped registry to read as disabled")
	}
}

func TestRegistry_Bootstrap(t *testing.T) {
	var gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		gotPath = req.URL.Path
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`[{"key":"AI_ADJUDICATION","enabled":true},{"key":"MOBILE_PWA","enabled":false}]`))
	}))
	defer srv.Close()

	r := New(srv.URL)
	if err := r.Bootstrap(context.Background()); err != nil {
		t.Fatalf("bootstrap: %v", err)
	}

	if gotPath != bootstrapPath {
		t.Fatalf("expected %s, got %s", bootstrapPath, gotPath)
	}
	if !r.IsEnabled("AI_ADJUDICATION") {
		t.Fatal("AI_ADJUDICATION should be enabled")
	}
	if r.IsEnabled("MOBILE_PWA") {
		t.Fatal("MOBILE_PWA should be disabled")
	}
	if r.IsEnabled("NOT_A_FLAG") {
		t.Fatal("unknown keys must read as disabled")
	}
}

func TestRegistry_BootstrapTrimsTrailingSlash(t *testing.T) {
	// Config values routinely carry a trailing slash; without trimming, the
	// request would hit a double-slashed path and 404.
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
	if r.IsEnabled("AI_ADJUDICATION") {
		t.Fatal("a failed bootstrap must leave every flag disabled")
	}
}

func TestRegistry_ApplyEvent(t *testing.T) {
	r := New("http://unused.invalid")

	r.apply([]byte(`{"key":"FRAUD_DETECTION","enabled":true,"updatedAt":"2026-09-20T12:00:00Z","actor":"admin@medfund.com"}`))
	if !r.IsEnabled("FRAUD_DETECTION") {
		t.Fatal("event should have enabled FRAUD_DETECTION")
	}

	r.apply([]byte(`{"key":"FRAUD_DETECTION","enabled":false}`))
	if r.IsEnabled("FRAUD_DETECTION") {
		t.Fatal("event should have disabled FRAUD_DETECTION")
	}
}

func TestRegistry_ApplyIgnoresBadPayloads(t *testing.T) {
	// A poisoned message must not stall the consumer or corrupt state - a
	// stalled consumer silently freezes every flag in the process.
	r := New("http://unused.invalid")
	r.apply([]byte(`{"key":"GROUP_PORTAL","enabled":true}`))

	r.apply([]byte(`not json`))
	r.apply([]byte(`{"enabled":true}`)) // no key

	if !r.IsEnabled("GROUP_PORTAL") {
		t.Fatal("bad payloads must leave existing state untouched")
	}
	if len(r.Snapshot()) != 1 {
		t.Fatalf("expected exactly one flag, got %v", r.Snapshot())
	}
}

func TestRegistry_SnapshotIsACopy(t *testing.T) {
	r := New("http://unused.invalid")
	r.apply([]byte(`{"key":"PROVIDER_PORTAL","enabled":true}`))

	snap := r.Snapshot()
	snap["PROVIDER_PORTAL"] = false

	if !r.IsEnabled("PROVIDER_PORTAL") {
		t.Fatal("mutating the snapshot must not affect the registry")
	}
}
