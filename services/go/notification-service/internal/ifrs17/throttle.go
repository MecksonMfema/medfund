package ifrs17

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

// Throttle is the SetNX-with-TTL primitive the dispatcher uses to
// suppress duplicate deliveries of the same (tenant, event type)
// within a window. Kept behind an interface so the unit tests can
// swap in an in-memory fake and so a nil Throttle degrades to
// "no throttling" (matching the DB-nil pattern used by other
// notification-service pipelines).
type Throttle interface {
	// Acquire returns true if the caller has the key for the next
	// window (i.e. should deliver). Returns false if a prior caller
	// already claimed the key inside the window. Returns an error
	// only for genuine backend failures; a full backend outage
	// surfaces as an error so the consumer can decide to still
	// deliver (fail-open) rather than silently drop.
	Acquire(ctx context.Context, key string, window time.Duration) (bool, error)
}

// RedisThrottle is a SetNX-based Throttle. Compatible with any
// go-redis v9 client (real or miniredis).
type RedisThrottle struct {
	Client *redis.Client
}

func NewRedisThrottle(client *redis.Client) *RedisThrottle {
	return &RedisThrottle{Client: client}
}

func (t *RedisThrottle) Acquire(ctx context.Context, key string, window time.Duration) (bool, error) {
	if t == nil || t.Client == nil {
		// Belt-and-braces — main() also guards on t==nil before
		// calling Dispatch. Fail-open so a nil throttle never
		// silently drops a delivery.
		return true, nil
	}
	if window <= 0 {
		// A window of zero (or negative) means "never throttle",
		// so short-circuit the round-trip.
		return true, nil
	}
	set, err := t.Client.SetNX(ctx, key, "1", window).Result()
	if err != nil {
		return false, fmt.Errorf("redis setnx: %w", err)
	}
	return set, nil
}

// throttleKey builds the dedupe key. Includes throttleMinutes so a
// recipient that later switches from 30m → 60m does not immediately
// stop hearing about events (the 30m key drains, the 60m key opens
// fresh).
func throttleKey(tenantID, eventType, recipient string, throttleMinutes int) string {
	return fmt.Sprintf("ifrs17:%s:%s:%s:%dm", tenantID, eventType, recipient, throttleMinutes)
}
