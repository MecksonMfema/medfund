//go:build integration

// Run with:  go test -tags=integration ./internal/handler/...
//
// Requires a working local Docker daemon — the test boots a postgres:17-alpine
// container via ory/dockertest, applies the schema, then exercises Handler end
// to end against a real *audit.Store backed by Postgres. Skipped by default
// `go test` runs so unit-CI stays fast and the developer machine does not need
// Docker. Cache is wired as nil — *cache.Cache methods all no-op on nil, which
// keeps the test focused on store/handler behaviour without needing Redis.

package handler

import (
	"context"
	"fmt"
	"io"
	"log"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/medfund/audit-service/internal/audit"
	"github.com/medfund/audit-service/internal/db"
	"github.com/ory/dockertest/v3"
	"github.com/ory/dockertest/v3/docker"
)

var integrationPool *pgxpool.Pool

func TestMain(m *testing.M) {
	dockerPool, err := dockertest.NewPool("")
	if err != nil {
		log.Fatalf("dockertest pool: %v", err)
	}
	if err := dockerPool.Client.Ping(); err != nil {
		log.Printf("Docker not reachable, skipping integration tests: %v", err)
		os.Exit(0)
	}

	resource, err := dockerPool.RunWithOptions(&dockertest.RunOptions{
		Repository: "postgres",
		Tag:        "17-alpine",
		Env: []string{
			"POSTGRES_USER=medfund",
			"POSTGRES_PASSWORD=medfund",
			"POSTGRES_DB=audit_test",
		},
	}, func(config *docker.HostConfig) {
		config.AutoRemove = true
		config.RestartPolicy = docker.RestartPolicy{Name: "no"}
	})
	if err != nil {
		log.Fatalf("start postgres: %v", err)
	}
	// 90 s safety net — CI cold-start can be slow on small runners.
	if err := resource.Expire(90); err != nil {
		log.Printf("expire: %v", err)
	}

	dsn := fmt.Sprintf("postgres://medfund:medfund@localhost:%s/audit_test?sslmode=disable",
		resource.GetPort("5432/tcp"))

	dockerPool.MaxWait = 30 * time.Second
	if err := dockerPool.Retry(func() error {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		p, err := db.Connect(ctx, dsn)
		if err != nil {
			return err
		}
		integrationPool = p
		return db.Migrate(context.Background(), integrationPool)
	}); err != nil {
		log.Fatalf("postgres never became reachable: %v", err)
	}

	code := m.Run()

	if integrationPool != nil {
		integrationPool.Close()
	}
	if err := dockerPool.Purge(resource); err != nil {
		log.Printf("purge: %v", err)
	}
	os.Exit(code)
}

// newHandler resets the audit_events table and returns a fresh Handler wired
// to the shared integration pool. Cache is nil — *cache.Cache methods all
// no-op on a nil receiver, which keeps these tests focused on store + handler
// behaviour without standing up Redis.
func newHandler(t *testing.T) (*fiber.App, *audit.Store) {
	t.Helper()
	if _, err := integrationPool.Exec(context.Background(), "TRUNCATE audit_events"); err != nil {
		t.Fatalf("truncate: %v", err)
	}
	store := audit.NewStore(integrationPool)
	h := New(store, nil)
	app := fiber.New()
	h.RegisterRoutes(app)
	return app, store
}

// tenantUUID is a fixed test UUID used wherever the handler scope check now
// requires a real UUID (callers that previously used short slugs like "t1"
// would now be rejected with 400).
const tenantUUID = "33333333-3333-3333-3333-333333333333"

func TestQueryEvents_EmptyStore_ReturnsOK(t *testing.T) {
	app, _ := newHandler(t)

	req := httptest.NewRequest("GET", "/api/v1/audit/events", nil)
	req.Header.Set("X-Tenant-ID", tenantUUID)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
}

func TestQueryEvents_WithEntityTypeFilter(t *testing.T) {
	app, store := newHandler(t)
	store.Append(audit.Event{ID: "1", TenantID: tenantUUID, EntityType: "Member", Action: "CREATE", Timestamp: time.Now()})
	store.Append(audit.Event{ID: "2", TenantID: tenantUUID, EntityType: "Claim", Action: "CREATE", Timestamp: time.Now()})

	req := httptest.NewRequest("GET", "/api/v1/audit/events?entityType=Member", nil)
	req.Header.Set("X-Tenant-ID", tenantUUID)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "Member") {
		t.Fatal("expected response to contain Member")
	}
	if strings.Contains(string(body), "Claim") {
		t.Fatal("expected response NOT to contain Claim when filtering by Member")
	}
}

func TestQueryEvents_WithActionFilter(t *testing.T) {
	app, store := newHandler(t)
	store.Append(audit.Event{ID: "1", TenantID: tenantUUID, EntityType: "Member", Action: "CREATE", Timestamp: time.Now()})
	store.Append(audit.Event{ID: "2", TenantID: tenantUUID, EntityType: "Member", Action: "UPDATE", Timestamp: time.Now()})

	req := httptest.NewRequest("GET", "/api/v1/audit/events?action=UPDATE", nil)
	req.Header.Set("X-Tenant-ID", tenantUUID)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "UPDATE") {
		t.Fatal("expected response to contain UPDATE")
	}
}

func TestQueryEvents_TenantIsolation(t *testing.T) {
	app, store := newHandler(t)
	// Handler only applies a tenant filter when X-Tenant-ID is a valid UUID,
	// so the two tenants must be UUIDs here. Non-UUIDs trigger the platform
	// admin view (no tenant filter at all) which would surface both rows.
	tenantA := "11111111-1111-1111-1111-111111111111"
	tenantB := "22222222-2222-2222-2222-222222222222"
	store.Append(audit.Event{ID: "1", TenantID: tenantA, EntityType: "Member", Action: "CREATE", Timestamp: time.Now()})
	store.Append(audit.Event{ID: "2", TenantID: tenantB, EntityType: "Claim", Action: "CREATE", Timestamp: time.Now()})

	req := httptest.NewRequest("GET", "/api/v1/audit/events", nil)
	req.Header.Set("X-Tenant-ID", tenantA)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), tenantA) {
		t.Fatalf("expected response to contain tenant-a events, got %s", string(body))
	}
	if strings.Contains(string(body), tenantB) {
		t.Fatalf("expected response NOT to contain tenant-b events, got %s", string(body))
	}
}

func TestQueryEvents_WithPagination(t *testing.T) {
	app, store := newHandler(t)
	for i := 0; i < 5; i++ {
		store.Append(audit.Event{
			ID:         string(rune('A' + i)),
			TenantID:   tenantUUID,
			EntityType: "Member",
			Action:     "CREATE",
			Timestamp:  time.Now().Add(time.Duration(i) * time.Second),
		})
	}

	req := httptest.NewRequest("GET", "/api/v1/audit/events?page=1&pageSize=2", nil)
	req.Header.Set("X-Tenant-ID", tenantUUID)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
}

// ─── Scope enforcement — added when handler stopped silently degrading to
// the platform view on a missing/invalid X-Tenant-ID header. ────────────────

func TestQueryEvents_rejectsMissingScope(t *testing.T) {
	app, _ := newHandler(t)
	req := httptest.NewRequest("GET", "/api/v1/audit/events", nil)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 400 {
		t.Fatalf("expected 400 for missing scope, got %d", resp.StatusCode)
	}
}

func TestQueryEvents_rejectsNonUUIDTenant(t *testing.T) {
	app, _ := newHandler(t)
	req := httptest.NewRequest("GET", "/api/v1/audit/events", nil)
	req.Header.Set("X-Tenant-ID", "not-a-uuid")
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 400 {
		t.Fatalf("expected 400 for non-UUID tenant, got %d", resp.StatusCode)
	}
}

func TestQueryEvents_acceptsPlatformScope(t *testing.T) {
	app, store := newHandler(t)
	otherTenant := "44444444-4444-4444-4444-444444444444"
	store.Append(audit.Event{ID: "1", TenantID: tenantUUID, EntityType: "Member", Action: "CREATE", Timestamp: time.Now()})
	store.Append(audit.Event{ID: "2", TenantID: otherTenant, EntityType: "Claim", Action: "CREATE", Timestamp: time.Now()})

	req := httptest.NewRequest("GET", "/api/v1/audit/events", nil)
	req.Header.Set("X-Platform-Scope", "all")
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200 for platform scope, got %d", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), tenantUUID) || !strings.Contains(string(body), otherTenant) {
		t.Fatalf("platform scope should surface every tenant's events, got %s", string(body))
	}
}

func TestGetDailyCounts_rejectsMissingScope(t *testing.T) {
	app, _ := newHandler(t)
	req := httptest.NewRequest("GET", "/api/v1/audit/events/daily-counts", nil)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 400 {
		t.Fatalf("expected 400, got %d", resp.StatusCode)
	}
}

func TestGetDailyCounts_filtersByTenant(t *testing.T) {
	app, store := newHandler(t)
	otherTenant := "44444444-4444-4444-4444-444444444444"
	now := time.Now()
	for i := 0; i < 3; i++ {
		store.Append(audit.Event{ID: fmt.Sprintf("a%d", i), TenantID: tenantUUID, EntityType: "M", Action: "CREATE", Timestamp: now})
	}
	for i := 0; i < 5; i++ {
		store.Append(audit.Event{ID: fmt.Sprintf("b%d", i), TenantID: otherTenant, EntityType: "M", Action: "CREATE", Timestamp: now})
	}

	req := httptest.NewRequest("GET", "/api/v1/audit/events/daily-counts?days=7", nil)
	req.Header.Set("X-Tenant-ID", tenantUUID)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)
	// Today's bucket should be 3 (this tenant only), never 8 (mixed).
	if !strings.Contains(string(body), "\"count\":3") {
		t.Fatalf("expected scoped count of 3 for today, got %s", string(body))
	}
	if strings.Contains(string(body), "\"count\":8") {
		t.Fatalf("daily counts must not bleed across tenants, got %s", string(body))
	}
}

func TestGetStats_EmptyStore_ReturnsZero(t *testing.T) {
	app, _ := newHandler(t)

	req := httptest.NewRequest("GET", "/api/v1/audit/stats", nil)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "0") {
		t.Fatalf("expected totalEvents=0, got %s", string(body))
	}
}

func TestGetStats_WithData_ReturnsCount(t *testing.T) {
	app, store := newHandler(t)
	store.Append(audit.Event{ID: "1", TenantID: "t1", Timestamp: time.Now()})
	store.Append(audit.Event{ID: "2", TenantID: "t1", Timestamp: time.Now()})
	store.Append(audit.Event{ID: "3", TenantID: "t1", Timestamp: time.Now()})

	req := httptest.NewRequest("GET", "/api/v1/audit/stats", nil)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "3") {
		t.Fatalf("expected totalEvents=3, got %s", string(body))
	}
}
