// Package flags gives Go services a read-only view of the platform feature
// flags that super admins toggle in the /platform/settings portal.
//
// Go services have no R2DBC access to public.platform_feature_flags, so
// unlike the Java shared FlagRegistry this one seeds itself over HTTP from
// tenancy-service's internal snapshot endpoint and is then kept current by
// the platform.feature-flags.v1 Kafka broadcast.
//
// Typical wiring in a service's main():
//
//	reg := flags.New(cfg.TenancyServiceURL)
//	if err := reg.Bootstrap(ctx); err != nil {
//	    log.Printf("[flags] bootstrap failed, all flags read as disabled: %v", err)
//	}
//	go reg.StartConsumer(ctx, cfg.KafkaBrokers, "my-service")
package flags

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

// Topic carries flag toggles. Additive-only: fields may be added to the
// payload, never removed or renamed without a v2 topic.
const Topic = "platform.feature-flags.v1"

const bootstrapPath = "/internal/v1/feature-flags"

// Registry holds the current flag state for one process. The zero value is
// not usable; call New.
type Registry struct {
	mu     sync.RWMutex
	values map[string]bool

	baseURL string
	client  *http.Client
}

// New returns a Registry that bootstraps from tenancyURL (e.g.
// "http://tenancy-service:8081"). Until Bootstrap succeeds every flag reads
// as disabled, which is the safe default for an unlaunched feature.
func New(tenancyURL string) *Registry {
	return &Registry{
		values:  make(map[string]bool),
		baseURL: strings.TrimRight(tenancyURL, "/"),
		client:  &http.Client{Timeout: 5 * time.Second},
	}
}

type flagRow struct {
	Key     string `json:"key"`
	Enabled bool   `json:"enabled"`
}

// Bootstrap seeds the cache from tenancy-service. Callers should log a
// failure and carry on rather than abort startup: an unreachable
// tenancy-service must not stop this service from serving traffic, and the
// first broadcast will correct any flag that matters.
func (r *Registry) Bootstrap(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, r.baseURL+bootstrapPath, nil)
	if err != nil {
		return fmt.Errorf("build flag bootstrap request: %w", err)
	}

	resp, err := r.client.Do(req)
	if err != nil {
		return fmt.Errorf("fetch flags from %s: %w", r.baseURL+bootstrapPath, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("flag bootstrap returned %d from %s", resp.StatusCode, r.baseURL+bootstrapPath)
	}

	var rows []flagRow
	if err := json.NewDecoder(resp.Body).Decode(&rows); err != nil {
		return fmt.Errorf("decode flag bootstrap payload: %w", err)
	}

	next := make(map[string]bool, len(rows))
	for _, row := range rows {
		next[row.Key] = row.Enabled
	}

	r.mu.Lock()
	r.values = next
	r.mu.Unlock()

	log.Printf("[flags] bootstrapped %d flags from %s", len(next), r.baseURL)
	return nil
}

// StartConsumer blocks reading flag-change events until ctx is cancelled, so
// callers run it in a goroutine. groupSuffix must be unique per service:
// every service needs its own consumer group or they would share partitions
// and only one would see each toggle.
func (r *Registry) StartConsumer(ctx context.Context, brokers string, groupSuffix string) {
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:  strings.Split(brokers, ","),
		Topic:    Topic,
		GroupID:  "flags-" + groupSuffix,
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
			log.Printf("[flags] %s", fmt.Sprintf(msg, args...))
		}),
	})
	defer reader.Close()

	log.Printf("[flags] watching %s as group flags-%s", Topic, groupSuffix)

	for {
		msg, err := reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return // graceful shutdown
			}
			log.Printf("[flags] fetch error: %v - retrying in 3s", err)
			select {
			case <-ctx.Done():
				return
			case <-time.After(3 * time.Second):
				continue
			}
		}

		r.apply(msg.Value)

		if err := reader.CommitMessages(ctx, msg); err != nil {
			log.Printf("[flags] commit error: %v", err)
		}
	}
}

// apply folds one event into the cache. A malformed payload is logged and
// skipped: a poisoned message must not stall the consumer, because a stalled
// consumer would silently freeze every flag in this process.
func (r *Registry) apply(data []byte) {
	var row flagRow
	if err := json.Unmarshal(data, &row); err != nil {
		log.Printf("[flags] failed to parse event: %v", err)
		return
	}
	if row.Key == "" {
		log.Printf("[flags] event without a key - skipping")
		return
	}

	r.mu.Lock()
	r.values[row.Key] = row.Enabled
	r.mu.Unlock()

	log.Printf("[flags] %s -> enabled=%t", row.Key, row.Enabled)
}

// IsEnabled reports whether the named flag is on. Unknown keys, and every key
// before a successful Bootstrap, read as false.
func (r *Registry) IsEnabled(key string) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.values[key]
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
