//go:build integration

// Run with:  go test -tags=integration ./internal/audit/...
//
// Query / pagination / filter coverage for *Store. Shares the postgres
// container TestMain set up in store_integration_test.go (same package).

package audit

import (
	"testing"
	"time"
)

func TestStore_Append_And_Count(t *testing.T) {
	resetTable(t)
	store := NewStore(integrationPool)
	if store.Count() != 0 {
		t.Fatal("expected empty store")
	}

	store.Append(Event{ID: "1", TenantID: "t1", EntityType: "Member", Action: "CREATE"})
	store.Append(Event{ID: "2", TenantID: "t1", EntityType: "Claim", Action: "CREATE"})

	if store.Count() != 2 {
		t.Fatalf("expected 2 events, got %d", store.Count())
	}
}

func TestStore_Query_ByTenantID(t *testing.T) {
	resetTable(t)
	store := NewStore(integrationPool)
	store.Append(Event{ID: "1", TenantID: "t1", EntityType: "Member", Action: "CREATE", Timestamp: time.Now()})
	store.Append(Event{ID: "2", TenantID: "t2", EntityType: "Member", Action: "CREATE", Timestamp: time.Now()})
	store.Append(Event{ID: "3", TenantID: "t1", EntityType: "Claim", Action: "UPDATE", Timestamp: time.Now()})

	results, total := store.Query(QueryFilter{TenantID: "t1"})
	if len(results) != 2 {
		t.Fatalf("expected 2 events for t1, got %d", len(results))
	}
	if total != 2 {
		t.Fatalf("expected total=2 for t1, got %d", total)
	}
}

func TestStore_Query_ByEntityType(t *testing.T) {
	resetTable(t)
	store := NewStore(integrationPool)
	store.Append(Event{ID: "1", TenantID: "t1", EntityType: "Member", Action: "CREATE", Timestamp: time.Now()})
	store.Append(Event{ID: "2", TenantID: "t1", EntityType: "Claim", Action: "CREATE", Timestamp: time.Now()})

	results, _ := store.Query(QueryFilter{TenantID: "t1", EntityType: "Claim"})
	if len(results) != 1 {
		t.Fatalf("expected 1 Claim event, got %d", len(results))
	}
}

func TestStore_Query_ByAction(t *testing.T) {
	resetTable(t)
	store := NewStore(integrationPool)
	store.Append(Event{ID: "1", TenantID: "t1", EntityType: "Member", Action: "CREATE", Timestamp: time.Now()})
	store.Append(Event{ID: "2", TenantID: "t1", EntityType: "Member", Action: "UPDATE", Timestamp: time.Now()})

	results, _ := store.Query(QueryFilter{TenantID: "t1", Action: "UPDATE"})
	if len(results) != 1 {
		t.Fatalf("expected 1 UPDATE event, got %d", len(results))
	}
}

func TestStore_Query_Pagination(t *testing.T) {
	resetTable(t)
	store := NewStore(integrationPool)
	base := time.Now()
	for i := 0; i < 10; i++ {
		store.Append(Event{
			ID:        string(rune('0' + i)),
			TenantID:  "t1",
			Timestamp: base.Add(time.Duration(i) * time.Second),
		})
	}

	results, total := store.Query(QueryFilter{TenantID: "t1", Page: 1, PageSize: 3})
	if len(results) != 3 {
		t.Fatalf("expected 3 events on page 1, got %d", len(results))
	}
	if total != 10 {
		t.Fatalf("expected total=10 ignoring pagination, got %d", total)
	}

	results, _ = store.Query(QueryFilter{TenantID: "t1", Page: 4, PageSize: 3})
	if len(results) != 1 {
		t.Fatalf("expected 1 event on page 4, got %d", len(results))
	}
}

func TestStore_Query_DateRange(t *testing.T) {
	resetTable(t)
	store := NewStore(integrationPool)
	yesterday := time.Now().Add(-24 * time.Hour)
	today := time.Now()

	store.Append(Event{ID: "1", TenantID: "t1", Timestamp: yesterday})
	store.Append(Event{ID: "2", TenantID: "t1", Timestamp: today})

	results, _ := store.Query(QueryFilter{
		TenantID:  "t1",
		StartDate: today.Add(-1 * time.Hour),
	})
	if len(results) != 1 {
		t.Fatalf("expected 1 recent event, got %d", len(results))
	}
}

func TestStore_Query_EmptyResult(t *testing.T) {
	resetTable(t)
	store := NewStore(integrationPool)
	store.Append(Event{ID: "1", TenantID: "t1", Timestamp: time.Now()})

	results, total := store.Query(QueryFilter{TenantID: "nonexistent"})
	if len(results) != 0 {
		t.Fatalf("expected empty results, got %d", len(results))
	}
	if total != 0 {
		t.Fatalf("expected total=0, got %d", total)
	}
}

