//go:build integration

// Run with:  go test -tags=integration ./internal/audit/...
//
// Requires a working local Docker daemon — the test boots a postgres:17-alpine
// container via ory/dockertest, applies the schema, then exercises Store.Append
// end-to-end. Skipped by default `go test` runs so unit-CI stays fast and the
// developer machine does not need Docker.

package audit

import (
	"context"
	"fmt"
	"log"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
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

func resetTable(t *testing.T) {
	t.Helper()
	_, err := integrationPool.Exec(context.Background(), "TRUNCATE audit_events")
	if err != nil {
		t.Fatalf("truncate: %v", err)
	}
}

func TestStoreAppend_persistsRowWithJsonbValues(t *testing.T) {
	resetTable(t)
	store := NewStore(integrationPool)

	store.Append(Event{
		ID:         "evt-1",
		TenantID:   "tenant-a",
		EntityType: "Scheme",
		EntityID:   "scheme-9",
		Action:     "CREATE",
		ActorID:    "user-1",
		OldValue:   nil,
		NewValue:   map[string]interface{}{"name": "Gold Plan", "status": "active"},
	})

	var name string
	err := integrationPool.QueryRow(context.Background(),
		`SELECT new_value->>'name' FROM audit_events WHERE id = $1`, "evt-1").Scan(&name)
	if err != nil {
		t.Fatalf("query: %v", err)
	}
	if name != "Gold Plan" {
		t.Errorf("expected new_value->>name = Gold Plan, got %q", name)
	}
}

func TestStoreAppend_isIdempotentOnDuplicateID(t *testing.T) {
	resetTable(t)
	store := NewStore(integrationPool)

	event := Event{
		ID:         "evt-dup",
		EntityType: "Claim",
		Action:     "UPDATE",
	}
	store.Append(event)
	store.Append(event) // replay — must not error, must not duplicate

	var count int
	err := integrationPool.QueryRow(context.Background(),
		`SELECT COUNT(*) FROM audit_events WHERE id = $1`, "evt-dup").Scan(&count)
	if err != nil {
		t.Fatalf("count: %v", err)
	}
	if count != 1 {
		t.Errorf("expected 1 row after duplicate append, got %d", count)
	}
}

func TestStoreAppend_firesOnAppendHookAfterPersist(t *testing.T) {
	resetTable(t)
	store := NewStore(integrationPool)

	fired := make(chan struct{}, 1)
	store.SetOnAppend(func() { fired <- struct{}{} })

	store.Append(Event{ID: "evt-hook", Action: "CREATE"})

	select {
	case <-fired:
		// good
	case <-time.After(2 * time.Second):
		t.Fatal("onAppend hook never fired")
	}
}

func TestStoreAppendSecurity_writesAuthEntityAndNormalizesActor(t *testing.T) {
	resetTable(t)
	store := NewStore(integrationPool)

	store.AppendSecurity(SecurityEvent{
		ID:         "sec-1",
		TenantID:   "tenant-a",
		EventType:  "LOGIN",
		UserID:     "user-1",
		ActorEmail: "alice@example.com",
		IPAddress:  "10.0.0.1",
	})

	var entityType, action, actorEmail string
	err := integrationPool.QueryRow(context.Background(),
		`SELECT entity_type, action, actor_email FROM audit_events WHERE id = $1`,
		"sec-1").Scan(&entityType, &action, &actorEmail)
	if err != nil {
		t.Fatalf("query: %v", err)
	}
	if entityType != "AUTH" || action != "LOGIN" || actorEmail != "alice@example.com" {
		t.Errorf("unexpected row: type=%s action=%s actor=%s", entityType, action, actorEmail)
	}
}

func TestStoreAppend_fillsTimestampWhenMissing(t *testing.T) {
	resetTable(t)
	store := NewStore(integrationPool)

	// Postgres TIMESTAMPTZ has microsecond precision, so truncate the Go
	// nanosecond bounds before comparing — otherwise the stored value can
	// round down to *just* before `before` and fail spuriously.
	before := time.Now().Truncate(time.Microsecond)
	store.Append(Event{ID: "evt-ts", Action: "CREATE"})
	after := time.Now()

	var ts time.Time
	err := integrationPool.QueryRow(context.Background(),
		`SELECT timestamp FROM audit_events WHERE id = $1`, "evt-ts").Scan(&ts)
	if err != nil {
		t.Fatalf("query: %v", err)
	}
	if ts.Before(before) || ts.After(after.Add(time.Second)) {
		t.Errorf("filled timestamp %v not within [%v, %v]", ts, before, after)
	}
}
