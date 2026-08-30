package scheduler

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/medfund/market-data-service/internal/adapters"
	"github.com/medfund/market-data-service/internal/tenancy"
)

// ── fakes ───────────────────────────────────────────────────────────

type fakeConfigs struct {
	rows []tenancy.MarketDataConfig
	err  error
}

func (f *fakeConfigs) ListEnabled(ctx context.Context) ([]tenancy.MarketDataConfig, error) {
	return f.rows, f.err
}

type published struct {
	tenantID string
	currency string
	source   string
}

type fakePublisher struct {
	calls []published
	err   error
}

func (p *fakePublisher) Publish(ctx context.Context, tenantID string, curve adapters.Curve, source string) error {
	p.calls = append(p.calls, published{tenantID: tenantID, currency: curve.Currency, source: source})
	return p.err
}

type fakeAdapter struct {
	source string
	curve  adapters.Curve
	err    error
}

func (a *fakeAdapter) Source() string { return a.source }
func (a *fakeAdapter) Fetch(ctx context.Context, currency string) (adapters.Curve, error) {
	return a.curve, a.err
}

// ── tests ───────────────────────────────────────────────────────────

func TestTick_PublishesEveryConfig(t *testing.T) {
	sched := &Scheduler{
		Configs: &fakeConfigs{rows: []tenancy.MarketDataConfig{
			{TenantID: "t1", Currency: "USD", Source: "RBZ_AUTO", AutoFetchEnabled: true},
			{TenantID: "t2", Currency: "ZAR", Source: "SARB_AUTO", AutoFetchEnabled: true},
		}},
		Adapter: func(src string) adapters.JurisdictionAdapter {
			return &fakeAdapter{
				source: src,
				curve: adapters.Curve{
					Currency:      "matched",
					EffectiveFrom: time.Now(),
					Points:        []adapters.Point{{TenorMonths: 12, SpotRate: "0.05"}},
				},
			}
		},
		Publisher: &fakePublisher{},
	}
	pub, fail := sched.Tick(context.Background())
	if pub != 2 || fail != 0 {
		t.Fatalf("published=%d failed=%d want 2/0", pub, fail)
	}
	calls := sched.Publisher.(*fakePublisher).calls
	if len(calls) != 2 {
		t.Fatalf("publisher calls=%d want 2", len(calls))
	}
	if calls[0].tenantID != "t1" || calls[0].source != "RBZ_AUTO" {
		t.Errorf("call[0]=%+v", calls[0])
	}
}

func TestTick_SkipsUnknownSource(t *testing.T) {
	sched := &Scheduler{
		Configs: &fakeConfigs{rows: []tenancy.MarketDataConfig{
			{TenantID: "t1", Currency: "USD", Source: "MYSTERY_AUTO", AutoFetchEnabled: true},
		}},
		Adapter:   func(src string) adapters.JurisdictionAdapter { return nil },
		Publisher: &fakePublisher{},
	}
	pub, fail := sched.Tick(context.Background())
	if pub != 0 || fail != 1 {
		t.Fatalf("published=%d failed=%d want 0/1 (unknown source counts as failed, not published)", pub, fail)
	}
}

func TestTick_ContinuesAfterAdapterError(t *testing.T) {
	sched := &Scheduler{
		Configs: &fakeConfigs{rows: []tenancy.MarketDataConfig{
			{TenantID: "t1", Currency: "USD", Source: "RBZ_AUTO", AutoFetchEnabled: true},
			{TenantID: "t2", Currency: "ZAR", Source: "SARB_AUTO", AutoFetchEnabled: true},
		}},
		Adapter: func(src string) adapters.JurisdictionAdapter {
			if src == "RBZ_AUTO" {
				return &fakeAdapter{source: src, err: errors.New("upstream 502")}
			}
			return &fakeAdapter{
				source: src,
				curve: adapters.Curve{
					Currency: "ZAR",
					Points:   []adapters.Point{{TenorMonths: 12, SpotRate: "0.09"}},
				},
			}
		},
		Publisher: &fakePublisher{},
	}
	pub, fail := sched.Tick(context.Background())
	if pub != 1 || fail != 1 {
		t.Fatalf("published=%d failed=%d want 1/1 — failing tuple must not abort the tick", pub, fail)
	}
}

func TestTick_EmptyListIsNoOp(t *testing.T) {
	sched := &Scheduler{
		Configs:   &fakeConfigs{rows: nil},
		Adapter:   adapters.For,
		Publisher: &fakePublisher{},
	}
	pub, fail := sched.Tick(context.Background())
	if pub != 0 || fail != 0 {
		t.Fatalf("published=%d failed=%d want 0/0", pub, fail)
	}
}

func TestTick_ConfigLookupFailureLogsAndReturnsZero(t *testing.T) {
	sched := &Scheduler{
		Configs:   &fakeConfigs{err: errors.New("tenancy down")},
		Adapter:   adapters.For,
		Publisher: &fakePublisher{},
	}
	pub, fail := sched.Tick(context.Background())
	if pub != 0 || fail != 0 {
		t.Fatalf("published=%d failed=%d want 0/0", pub, fail)
	}
}
