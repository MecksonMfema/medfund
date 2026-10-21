// Package scheduler drives the per-tick fetch loop. Kept independent
// of main.go so tests can drive Run() with fakes for the tenancy
// lookup, adapter map, and publisher.
package scheduler

import (
	"context"
	"errors"
	"log"

	"github.com/medfund/market-data-service/internal/adapters"
	"github.com/medfund/market-data-service/internal/publisher"
	"github.com/medfund/market-data-service/internal/tenancy"
)

// Publisher is the subset of publisher.Publisher the scheduler needs.
type Publisher interface {
	Publish(ctx context.Context, tenantID string, curve adapters.Curve, source string) error
}

// AdapterLookup returns the JurisdictionAdapter for a source string,
// or nil for unknown sources.
type AdapterLookup func(source string) adapters.JurisdictionAdapter

// Scheduler pulls enabled configs each tick, iterates the matching
// adapter over each config's currency, and hands the resulting curve
// to the publisher. Errors are per-config: one failing tuple never
// prevents the rest of the tick from firing.
type Scheduler struct {
	Configs   tenancy.ConfigLookup
	Adapter   AdapterLookup
	Publisher Publisher
}

// New wires a Scheduler with the production adapter map + Kafka
// publisher. Tests build a Scheduler directly with fakes.
func New(configs tenancy.ConfigLookup, pub Publisher) *Scheduler {
	return &Scheduler{
		Configs:   configs,
		Adapter:   adapters.For,
		Publisher: pub,
	}
}

// Tick runs one fetch pass. Returns the number of successful
// publishes + the count of failures for the caller to log.
func (s *Scheduler) Tick(ctx context.Context) (published int, failed int) {
	if s == nil || s.Configs == nil || s.Publisher == nil {
		log.Printf("[scheduler] tick skipped — configs=%v publisher=%v", s.Configs != nil, s.Publisher != nil)
		return 0, 0
	}
	configs, err := s.Configs.ListEnabled(ctx)
	if err != nil {
		log.Printf("[scheduler] list enabled failed: %v", err)
		return 0, 0
	}
	if len(configs) == 0 {
		log.Printf("[scheduler] no enabled market-data configs; nothing to fetch")
		return 0, 0
	}
	for _, cfg := range configs {
		if err := s.fetchOne(ctx, cfg); err != nil {
			log.Printf("[scheduler] fetch failed tenant=%s currency=%s source=%s: %v",
				cfg.TenantID, cfg.Currency, cfg.Source, err)
			failed++
			continue
		}
		published++
	}
	log.Printf("[scheduler] tick complete: published=%d failed=%d total=%d",
		published, failed, len(configs))
	return published, failed
}

func (s *Scheduler) fetchOne(ctx context.Context, cfg tenancy.MarketDataConfig) error {
	adapter := s.Adapter(cfg.Source)
	if adapter == nil {
		return errors.New("no adapter registered for source " + cfg.Source)
	}
	curve, err := adapter.Fetch(ctx, cfg.Currency)
	if err != nil {
		return err
	}
	return s.Publisher.Publish(ctx, cfg.TenantID, curve, adapter.Source())
}

// Verify at compile time that publisher.Publisher satisfies the local
// interface. Prevents the interface from silently drifting from the
// concrete type.
var _ Publisher = (*publisher.Publisher)(nil)
