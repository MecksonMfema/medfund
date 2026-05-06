package handler

import (
	"encoding/json"
	"fmt"
	"strconv"
	"time"

	"github.com/gofiber/fiber/v2"

	"github.com/medfund/audit-service/internal/audit"
	"github.com/medfund/audit-service/internal/cache"
)

type Handler struct {
	store *audit.Store
	cache *cache.Cache // nil when Redis is unavailable — caching is skipped gracefully
}

func New(store *audit.Store, c *cache.Cache) *Handler {
	return &Handler{store: store, cache: c}
}

// isValidUUID returns true when s is a standard hyphenated UUID.
func isValidUUID(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i, c := range s {
		switch i {
		case 8, 13, 18, 23:
			if c != '-' {
				return false
			}
		default:
			if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
				return false
			}
		}
	}
	return true
}

func (h *Handler) QueryEvents(c *fiber.Ctx) error {
	startDateStr := c.Query("startDate")
	endDateStr := c.Query("endDate")
	pageStr := c.Query("page")
	pageSizeStr := c.Query("pageSize")
	queryStr := c.Query("q")

	tenantHeader := c.Get("X-Tenant-ID")
	// Non-UUID / absent header = platform admin view — no tenant filter applied,
	// so the response includes events from every tenant. A valid UUID narrows
	// the result set to that tenant alone.
	tenantFilter := ""
	if tenantHeader != "" && isValidUUID(tenantHeader) {
		tenantFilter = tenantHeader
	}

	filter := audit.QueryFilter{
		TenantID:   tenantFilter,
		EntityType: c.Query("entityType"),
		EntityID:   c.Query("entityId"),
		Action:     c.Query("action"),
		ActorID:    c.Query("actorId"),
		Query:      queryStr,
	}
	if startDateStr != "" {
		if t, err := time.Parse("2006-01-02", startDateStr); err == nil {
			filter.StartDate = t
		}
	}
	if endDateStr != "" {
		if t, err := time.Parse("2006-01-02", endDateStr); err == nil {
			filter.EndDate = t.Add(24 * time.Hour)
		}
	}
	if pageStr != "" {
		filter.Page, _ = strconv.Atoi(pageStr)
	}
	if pageSizeStr != "" {
		filter.PageSize, _ = strconv.Atoi(pageSizeStr)
	}

	cacheKey := cache.BuildKey(
		filter.TenantID, filter.EntityType, filter.EntityID,
		filter.Action, filter.ActorID, queryStr,
		startDateStr, endDateStr,
		filter.Page, filter.PageSize,
	)

	// Cache hit — return stored JSON directly without touching the store.
	if cached, ok := h.cache.Get(c.Context(), cacheKey); ok {
		c.Set("X-Cache", "HIT")
		c.Set("Content-Type", "application/json")
		return c.SendString(cached)
	}

	// Cache miss — query the store and populate the cache.
	events, total := h.store.Query(filter)
	result := fiber.Map{
		"events": events,
		"total":  total,
		"page":   filter.Page,
	}

	if body, err := json.Marshal(result); err == nil {
		h.cache.Set(c.Context(), cacheKey, string(body))
	}

	c.Set("X-Cache", "MISS")
	return c.JSON(result)
}

func (h *Handler) GetStats(c *fiber.Ctx) error {
	return c.JSON(fiber.Map{
		"totalEvents": h.store.Count(),
	})
}

// GetDailyCounts returns event counts grouped by day for the last N days.
// The result is a continuous series (zero-filled) ready for the chart — the
// frontend maps it directly with no calculations.
func (h *Handler) GetDailyCounts(c *fiber.Ctx) error {
	tenantHeader := c.Get("X-Tenant-ID")
	// Non-UUID / absent = platform view, aggregate across all tenants.
	tenantID := ""
	if tenantHeader != "" && isValidUUID(tenantHeader) {
		tenantID = tenantHeader
	}
	days, _ := strconv.Atoi(c.Query("days", "30"))
	if days <= 0 || days > 365 {
		days = 30
	}

	cacheKey := fmt.Sprintf("daily-counts:%s:%d", tenantID, days)
	if cached, ok := h.cache.Get(c.Context(), cacheKey); ok {
		c.Set("X-Cache", "HIT")
		c.Set("Content-Type", "application/json")
		return c.SendString(cached)
	}

	counts := h.store.DailyCounts(tenantID, days)
	if body, err := json.Marshal(counts); err == nil {
		h.cache.Set(c.Context(), cacheKey, string(body))
	}
	c.Set("X-Cache", "MISS")
	return c.JSON(counts)
}

func (h *Handler) RegisterRoutes(app *fiber.App) {
	api := app.Group("/api/v1/audit")
	api.Get("/events", h.QueryEvents)
	api.Get("/events/daily-counts", h.GetDailyCounts)
	api.Get("/stats", h.GetStats)
}
