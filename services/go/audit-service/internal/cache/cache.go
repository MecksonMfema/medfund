package cache

import (
	"context"
	"fmt"
	"log"
	"time"

	"github.com/redis/go-redis/v9"
)

// TTL is how long a cached query result lives before being considered stale.
// Audit queries are invalidated immediately when a new event arrives, so this
// is a safety-net expiry rather than the primary freshness mechanism.
const TTL = 60 * time.Second

// keyPrefix namespaces all audit query cache entries in Redis.
const keyPrefix = "audit:q:"

// Cache wraps a Redis client and provides typed get/set/invalidate operations
// for audit query results. All methods are safe to call on a nil *Cache —
// caching is silently skipped so the service degrades gracefully when Redis
// is unavailable.
type Cache struct {
	client *redis.Client
}

// New connects to Redis at the given URL and verifies connectivity with a ping.
// Returns an error if the URL is invalid or Redis is unreachable.
func New(redisURL string) (*Cache, error) {
	opts, err := redis.ParseURL(redisURL)
	if err != nil {
		return nil, fmt.Errorf("invalid Redis URL: %w", err)
	}
	client := redis.NewClient(opts)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := client.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("Redis ping failed: %w", err)
	}
	log.Printf("[audit-cache] connected to Redis at %s", redisURL)
	return &Cache{client: client}, nil
}

// Get retrieves a cached JSON response for the given cache key.
// Returns ("", false) on a miss or any Redis error.
func (c *Cache) Get(ctx context.Context, key string) (string, bool) {
	if c == nil {
		return "", false
	}
	val, err := c.client.Get(ctx, key).Result()
	if err != nil {
		return "", false
	}
	return val, true
}

// Set stores a JSON response string under the given key with the standard TTL.
// Errors are logged but not returned — a failed cache write is not fatal.
func (c *Cache) Set(ctx context.Context, key, value string) {
	if c == nil {
		return
	}
	if err := c.client.Set(ctx, key, value, TTL).Err(); err != nil {
		log.Printf("[audit-cache] set error for key %s: %v", key, err)
	}
}

// Invalidate deletes all cached audit query entries.
// Called whenever a new event is appended so the next request sees fresh data.
func (c *Cache) Invalidate(ctx context.Context) {
	if c == nil {
		return
	}
	keys, err := c.client.Keys(ctx, keyPrefix+"*").Result()
	if err != nil || len(keys) == 0 {
		return
	}
	if err := c.client.Del(ctx, keys...).Err(); err != nil {
		log.Printf("[audit-cache] invalidate error: %v", err)
	}
}

// BuildKey returns a deterministic cache key for a query described by the
// given parameters. All parameters are included so different filter
// combinations always produce different keys.
func BuildKey(tenantID, entityType, entityID, action, actorID, query, startDate, endDate string, page, pageSize int) string {
	return fmt.Sprintf("%s%s:%s:%s:%s:%s:%s:%s:%s:%d:%d",
		keyPrefix,
		tenantID, entityType, entityID,
		action, actorID, query,
		startDate, endDate,
		page, pageSize,
	)
}

// Close releases the underlying Redis connection.
func (c *Cache) Close() error {
	if c == nil {
		return nil
	}
	return c.client.Close()
}
