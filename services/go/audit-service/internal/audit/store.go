package audit

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Store persists audit events to PostgreSQL and exposes query/count operations.
// All methods are safe for concurrent use.
type Store struct {
	pool     *pgxpool.Pool
	onAppend func() // optional hook for cache invalidation — called after every Append
}

func NewStore(pool *pgxpool.Pool) *Store {
	return &Store{pool: pool}
}

// SetOnAppend registers a callback invoked (in a goroutine) after each
// successful Append. Used to invalidate the Redis query cache without
// coupling the Store to any specific cache implementation.
func (s *Store) SetOnAppend(fn func()) {
	s.onAppend = fn
}

// Append persists a single audit event to the database.
// Duplicate IDs are silently ignored (ON CONFLICT DO NOTHING) so replaying
// Kafka messages from FirstOffset after a restart is idempotent.
func (s *Store) Append(event Event) {
	if event.Timestamp.IsZero() {
		event.Timestamp = time.Now()
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	oldJSON := marshalJSON(event.OldValue)
	newJSON := marshalJSON(event.NewValue)

	_, err := s.pool.Exec(ctx, `
		INSERT INTO audit_events
			(id, tenant_id, entity_type, entity_id, entity_name, action,
			 actor_id, actor_email, old_value, new_value, changed_fields,
			 correlation_id, timestamp)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9::jsonb,$10::jsonb,$11,$12,$13)
		ON CONFLICT (id) DO NOTHING`,
		event.ID, event.TenantID, event.EntityType, event.EntityID, event.EntityName,
		event.Action, event.ActorID, event.ActorEmail,
		oldJSON, newJSON, event.ChangedFields,
		event.CorrelationID, event.Timestamp,
	)
	if err != nil {
		log.Printf("[audit-store] failed to persist event %s: %v", event.ID, err)
		return
	}

	if s.onAppend != nil {
		go s.onAppend()
	}
}

// AppendSecurity normalizes a Keycloak security event into the shared
// audit_events table. No separate table — everything lives in one place.
func (s *Store) AppendSecurity(event SecurityEvent) {
	if event.Timestamp.IsZero() {
		event.Timestamp = time.Now()
	}

	entityName := event.ActorEmail
	if entityName == "" {
		entityName = event.UserID
	}

	details := map[string]interface{}{
		"ipAddress": event.IPAddress,
		"userAgent": event.UserAgent,
	}
	if event.Details != "" && event.Details != "{}" {
		details["details"] = event.Details
	}

	s.Append(Event{
		ID:         event.ID,
		TenantID:   event.TenantID,
		EntityType: "AUTH",
		EntityID:   event.UserID,
		EntityName: entityName,
		Action:     event.EventType,
		ActorID:    event.UserID,
		ActorEmail: event.ActorEmail,
		NewValue:   details,
		Timestamp:  event.Timestamp,
	})
}

// Query returns the matching events for the given filter and the total number
// of matching rows (ignoring pagination) in a single database round-trip using
// a window function.
func (s *Store) Query(filter QueryFilter) ([]Event, int) {
	pageSize := filter.PageSize
	if pageSize <= 0 {
		pageSize = 50
	}
	if pageSize > 200 {
		pageSize = 200
	}
	page := filter.Page
	if page <= 0 {
		page = 1
	}
	offset := (page - 1) * pageSize

	whereClause, args := buildWhere(filter)
	limitArg := len(args) + 1
	offsetArg := len(args) + 2

	query := fmt.Sprintf(`
		SELECT
			id, tenant_id, entity_type, entity_id, entity_name, action,
			actor_id, actor_email, old_value, new_value, changed_fields,
			correlation_id, timestamp,
			COUNT(*) OVER() AS total
		FROM audit_events
		%s
		ORDER BY timestamp DESC
		LIMIT $%d OFFSET $%d`,
		whereClause, limitArg, offsetArg,
	)
	args = append(args, pageSize, offset)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	rows, err := s.pool.Query(ctx, query, args...)
	if err != nil {
		log.Printf("[audit-store] query error: %v", err)
		return nil, 0
	}
	defer rows.Close()

	var events []Event
	total := 0

	for rows.Next() {
		var e Event
		var oldJSON, newJSON []byte
		var changedFields []string

		if err := rows.Scan(
			&e.ID, &e.TenantID, &e.EntityType, &e.EntityID, &e.EntityName,
			&e.Action, &e.ActorID, &e.ActorEmail,
			&oldJSON, &newJSON, &changedFields,
			&e.CorrelationID, &e.Timestamp,
			&total,
		); err != nil {
			log.Printf("[audit-store] scan error: %v", err)
			continue
		}

		if oldJSON != nil {
			_ = json.Unmarshal(oldJSON, &e.OldValue)
		}
		if newJSON != nil {
			_ = json.Unmarshal(newJSON, &e.NewValue)
		}
		e.ChangedFields = changedFields

		events = append(events, e)
	}

	return events, total
}

// DailyCounts returns event counts grouped by calendar day (UTC) for the last
// `days` days, scoped to `tenantID` when non-empty. Every day in the range is
// always present in the result (count = 0 for days with no events), giving the
// frontend a continuous series ready for charting — no client-side calculation.
func (s *Store) DailyCounts(tenantID string, days int) []DailyCount {
	if days <= 0 || days > 365 {
		days = 30
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	since := time.Now().UTC().AddDate(0, 0, -days)

	var query string
	var args []interface{}
	if tenantID != "" {
		query = `
			SELECT TO_CHAR(timestamp AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS date,
			       COUNT(*) AS count
			FROM audit_events
			WHERE tenant_id = $1 AND timestamp >= $2
			GROUP BY 1 ORDER BY 1 ASC`
		args = []interface{}{tenantID, since}
	} else {
		query = `
			SELECT TO_CHAR(timestamp AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS date,
			       COUNT(*) AS count
			FROM audit_events
			WHERE timestamp >= $1
			GROUP BY 1 ORDER BY 1 ASC`
		args = []interface{}{since}
	}

	rows, err := s.pool.Query(ctx, query, args...)
	if err != nil {
		log.Printf("[audit-store] daily counts error: %v", err)
		return s.emptyDailyCounts(days)
	}
	defer rows.Close()

	// Seed every day in the range with 0 so the series is always continuous.
	counts := make(map[string]int, days)
	for i := days - 1; i >= 0; i-- {
		d := time.Now().UTC().AddDate(0, 0, -i)
		counts[d.Format("2006-01-02")] = 0
	}

	for rows.Next() {
		var date string
		var count int
		if err := rows.Scan(&date, &count); err != nil {
			log.Printf("[audit-store] daily counts scan error: %v", err)
			continue
		}
		counts[date] = count
	}

	// Return as an ordered slice oldest → newest.
	result := make([]DailyCount, 0, days)
	for i := days - 1; i >= 0; i-- {
		d := time.Now().UTC().AddDate(0, 0, -i)
		date := d.Format("2006-01-02")
		result = append(result, DailyCount{Date: date, Count: counts[date]})
	}
	return result
}

func (s *Store) emptyDailyCounts(days int) []DailyCount {
	result := make([]DailyCount, days)
	for i := range result {
		d := time.Now().UTC().AddDate(0, 0, -(days - 1 - i))
		result[i] = DailyCount{Date: d.Format("2006-01-02"), Count: 0}
	}
	return result
}

// Count returns the total number of audit events stored.
func (s *Store) Count() int {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var count int
	if err := s.pool.QueryRow(ctx, "SELECT COUNT(*) FROM audit_events").Scan(&count); err != nil {
		log.Printf("[audit-store] count error: %v", err)
		return 0
	}
	return count
}

// ── helpers ───────────────────────────────────────────────────────────────────

// buildWhere constructs a parameterised WHERE clause from the filter.
// Returns ("", nil) when no conditions are set.
func buildWhere(f QueryFilter) (string, []interface{}) {
	var clauses []string
	var args []interface{}
	n := 0

	add := func(clause string, arg interface{}) {
		n++
		clauses = append(clauses, fmt.Sprintf(clause, n))
		args = append(args, arg)
	}

	if f.TenantID != "" {
		add("tenant_id = $%d", f.TenantID)
	}
	if f.EntityType != "" {
		add("lower(entity_type) = lower($%d)", f.EntityType)
	}
	if f.EntityID != "" {
		add("entity_id = $%d", f.EntityID)
	}
	if f.Action != "" {
		add("lower(action) = lower($%d)", f.Action)
	}
	if f.ActorID != "" {
		add("actor_id = $%d", f.ActorID)
	}
	if !f.StartDate.IsZero() {
		add("timestamp >= $%d", f.StartDate)
	}
	if !f.EndDate.IsZero() {
		add("timestamp <= $%d", f.EndDate)
	}
	if f.Query != "" {
		n++
		// Single arg searched across all identifier fields using ILIKE.
		clauses = append(clauses, fmt.Sprintf(
			`lower(entity_type||' '||entity_id||' '||entity_name||' '||actor_id||' '||actor_email) LIKE '%%'||lower($%d)||'%%'`,
			n,
		))
		args = append(args, f.Query)
	}

	if len(clauses) == 0 {
		return "", args
	}
	return "WHERE " + strings.Join(clauses, " AND "), args
}

// marshalJSON encodes v as JSON bytes for JSONB insertion.
// Returns nil (stored as SQL NULL) when v is nil or an empty map.
func marshalJSON(v map[string]interface{}) []byte {
	if len(v) == 0 {
		return nil
	}
	b, err := json.Marshal(v)
	if err != nil {
		return nil
	}
	return b
}
