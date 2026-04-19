package db

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// schema is run on every startup. All DDL is idempotent (IF NOT EXISTS).
const schema = `
CREATE TABLE IF NOT EXISTS audit_events (
    id             TEXT        PRIMARY KEY,
    tenant_id      TEXT        NOT NULL DEFAULT '',
    entity_type    TEXT        NOT NULL DEFAULT '',
    entity_id      TEXT        NOT NULL DEFAULT '',
    entity_name    TEXT        NOT NULL DEFAULT '',
    action         TEXT        NOT NULL DEFAULT '',
    actor_id       TEXT        NOT NULL DEFAULT '',
    actor_email    TEXT        NOT NULL DEFAULT '',
    old_value      JSONB,
    new_value      JSONB,
    changed_fields TEXT[],
    correlation_id TEXT        NOT NULL DEFAULT '',
    timestamp      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ae_tenant  ON audit_events(tenant_id);
CREATE INDEX IF NOT EXISTS idx_ae_type    ON audit_events(lower(entity_type));
CREATE INDEX IF NOT EXISTS idx_ae_action  ON audit_events(lower(action));
CREATE INDEX IF NOT EXISTS idx_ae_actor   ON audit_events(actor_id);
CREATE INDEX IF NOT EXISTS idx_ae_ts      ON audit_events(timestamp DESC);
`

// Connect creates a pgx connection pool and verifies connectivity.
func Connect(ctx context.Context, databaseURL string) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, fmt.Errorf("parse db config: %w", err)
	}
	cfg.MaxConns = 10
	cfg.MinConns = 2
	cfg.MaxConnLifetime = 30 * time.Minute
	cfg.MaxConnIdleTime = 5 * time.Minute

	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("create pool: %w", err)
	}

	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := pool.Ping(pingCtx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("ping database: %w", err)
	}
	return pool, nil
}

// Migrate runs the schema DDL. All statements are idempotent so it is safe
// to call on every startup.
func Migrate(ctx context.Context, pool *pgxpool.Pool) error {
	if _, err := pool.Exec(ctx, schema); err != nil {
		return fmt.Errorf("schema migration: %w", err)
	}
	return nil
}
