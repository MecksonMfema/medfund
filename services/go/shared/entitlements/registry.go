// Package entitlements gives Go services a read-only view of the per-tenant
// module entitlements that super admins toggle in the platform portal — the
// sellable-horizontal gate (KYC is the first consumer).
//
// Unlike the Java shared TenantEntitlementRegistry, Go services have no R2DBC
// access to public.tenant_module_entitlements, so this registry seeds itself
// over HTTP from tenancy-service's internal snapshot endpoint and is then kept
// current by the platform.module-entitlements.v1 Kafka broadcast.
//
// Entitlement is per (tenant, module): the cache is keyed by both, and the
// safe default is NOT entitled — a module is off until sold, and an
// un-bootstrapped (or unreachable-tenancy) registry must never hand a paid
// module away (fail closed, G3).
//
// Typical wiring in a service's main():
//
//	reg := entitlements.New(cfg.TenancyServiceURL)
//	if err := reg.Bootstrap(ctx); err != nil {
//	    log.Printf("[entitlements] bootstrap failed, all modules read as not-entitled: %v", err)
//	}
//	go reg.StartConsumer(ctx, cfg.KafkaBrokers, "my-service")
package entitlements

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/segmentio/kafka-go"
)

// Topic carries entitlement toggles. Additive-only: fields may be added to the
// payload, never removed or renamed without a v2 topic.
const Topic = "platform.module-entitlements.v1"

const bootstrapPath = "/internal/v1/module-entitlements"

// key composes the tenant and module into a single map key. Keeping it in one
// place means Bootstrap, apply, and IsEntitled can never disagree on the shape.
func key(tenantID, module string) string {
	return tenantID + "|" + module
}

// Registry holds the current entitlement state for one process. The zero value
// is not usable; call New.
type Registry struct {
	mu     sync.RWMutex
	values map[string]bool

	baseURL string
	client  *http.Client
}

// New returns a Registry that bootstraps from tenancyURL (e.g.
// "http://tenancy-service:8081"). Until Bootstrap succeeds every module reads
// as not-entitled, which is the safe default for an unsold capability.
func New(tenancyURL string) *Registry {
	return &Registry{
		values:  make(map[string]bool),
		baseURL: strings.TrimRight(tenancyURL, "/"),
		client:  &http.Client{Timeout: 5 * time.Second},
	}
}

type entitlementRow struct {
	TenantID  string `json:"tenantId"`
	ModuleKey string `json:"moduleKey"`
	Enabled   bool   `json:"enabled"`
}

// Bootstrap seeds the cache from tenancy-service. Callers should log a failure
// and carry on rather than abort startup: an unreachable tenancy-service must
// not stop the gateway from serving traffic, and the gate fails closed (every
// module reads as not-entitled) until the snapshot or first broadcast arrives.
func (r *Registry) Bootstrap(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, r.baseURL+bootstrapPath, nil)
	if err != nil {
		return fmt.Errorf("build entitlement bootstrap request: %w", err)
	}

	resp, err := r.client.Do(req)
	if err != nil {
		return fmt.Errorf("fetch entitlements from %s: %w", r.baseURL+bootstrapPath, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("entitlement bootstrap returned %d from %s", resp.StatusCode, r.baseURL+bootstrapPath)
	}

	var rows []entitlementRow
	if err := json.NewDecoder(resp.Body).Decode(&rows); err != nil {
		return fmt.Errorf("decode entitlement bootstrap payload: %w", err)
	}

	next := make(map[string]bool, len(rows))
	for _, row := range rows {
		next[key(row.TenantID, row.ModuleKey)] = row.Enabled
	}

	r.mu.Lock()
	r.values = next
	r.mu.Unlock()

	log.Printf("[entitlements] bootstrapped %d entitlements from %s", len(next), r.baseURL)
	return nil
}

// StartConsumer blocks reading entitlement-change events until ctx is
// cancelled, so callers run it in a goroutine. groupSuffix must be unique per
// service: every service needs its own consumer group or they would share
// partitions and only one would see each toggle.
func (r *Registry) StartConsumer(ctx context.Context, brokers string, groupSuffix string) {
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:  strings.Split(brokers, ","),
		Topic:    Topic,
		GroupID:  "entitlements-" + groupSuffix,
		MinBytes: 1,
		MaxBytes: 1 << 20,
		MaxWait:  500 * time.Millisecond,
		// LastOffset, not FirstOffset: Bootstrap already established the
		// current state, so replaying the backlog would only re-apply
		// historical toggles that the snapshot already reflects.
		StartOffset:    kafka.LastOffset,
		CommitInterval: time.Second,
		Logger:         kafka.LoggerFunc(func(msg string, args ...interface{}) {}),
		ErrorLogger: kafka.LoggerFunc(func(msg string, args ...interface{}) {
			log.Printf("[entitlements] %s", fmt.Sprintf(msg, args...))
		}),
	})
	defer reader.Close()

	log.Printf("[entitlements] watching %s as group entitlements-%s", Topic, groupSuffix)

	for {
		msg, err := reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return // graceful shutdown
			}
			log.Printf("[entitlements] fetch error: %v - retrying in 3s", err)
			select {
			case <-ctx.Done():
				return
			case <-time.After(3 * time.Second):
				continue
			}
		}

		r.apply(msg.Value)

		if err := reader.CommitMessages(ctx, msg); err != nil {
			log.Printf("[entitlements] commit error: %v", err)
		}
	}
}

// apply folds one event into the cache. A malformed payload is logged and
// skipped: a poisoned message must not stall the consumer, because a stalled
// consumer would silently freeze every entitlement in this process.
func (r *Registry) apply(data []byte) {
	var row entitlementRow
	if err := json.Unmarshal(data, &row); err != nil {
		log.Printf("[entitlements] failed to parse event: %v", err)
		return
	}
	if row.TenantID == "" || row.ModuleKey == "" {
		log.Printf("[entitlements] event missing tenantId/moduleKey - skipping")
		return
	}

	r.mu.Lock()
	r.values[key(row.TenantID, row.ModuleKey)] = row.Enabled
	r.mu.Unlock()

	log.Printf("[entitlements] tenant=%s module=%s -> enabled=%t", row.TenantID, row.ModuleKey, row.Enabled)
}

// IsEntitled reports whether the tenant is entitled to the named module.
// Unknown (tenant, module) pairs, and every pair before a successful Bootstrap,
// read as false — the fail-closed default.
func (r *Registry) IsEntitled(tenantID, module string) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.values[key(tenantID, module)]
}

// Snapshot returns a copy of the current state, for health or debug endpoints.
func (r *Registry) Snapshot() map[string]bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make(map[string]bool, len(r.values))
	for k, v := range r.values {
		out[k] = v
	}
	return out
}
