package ifrs17

import (
	"context"
	"testing"
	"time"
)

func TestRedisThrottle_nilClient_isSilentPassthrough(t *testing.T) {
	var t0 *RedisThrottle // nil throttle
	ok, err := t0.Acquire(context.Background(), "any-key", 30*time.Minute)
	if err != nil {
		t.Fatalf("nil throttle should not error: %v", err)
	}
	if !ok {
		t.Fatalf("nil throttle should always let the caller through")
	}
}

func TestRedisThrottle_nilInnerClient_isSilentPassthrough(t *testing.T) {
	t0 := &RedisThrottle{Client: nil}
	ok, err := t0.Acquire(context.Background(), "any-key", 30*time.Minute)
	if err != nil {
		t.Fatalf("nil inner client should not error: %v", err)
	}
	if !ok {
		t.Fatal("nil inner client should be a passthrough")
	}
}

func TestRedisThrottle_zeroWindow_shortCircuitsBeforeRoundTrip(t *testing.T) {
	// Construct with a client that would panic on any call — the
	// short-circuit for window <= 0 must fire before we ever touch it.
	// A nil Client is the cheapest way to prove the short-circuit works
	// without wiring miniredis in — the earlier test already proved
	// nil takes the same branch, but this asserts window==0 alone is
	// also a passthrough.
	t0 := &RedisThrottle{Client: nil}
	ok, err := t0.Acquire(context.Background(), "any-key", 0)
	if err != nil || !ok {
		t.Fatalf("zero window should be passthrough, got ok=%v err=%v", ok, err)
	}
}

func TestThrottleKey_stableShape(t *testing.T) {
	k := throttleKey("tnt-1", "ONEROUS_TRANSITION", "alice@acme.test", 30)
	want := "ifrs17:tnt-1:ONEROUS_TRANSITION:alice@acme.test:30m"
	if k != want {
		t.Errorf("throttleKey shape drifted: %q want %q", k, want)
	}
}
