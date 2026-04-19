package handler

import (
	"encoding/json"
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

func (h *Handler) QueryEvents(c *fiber.Ctx) error {
	startDateStr := c.Query("startDate")
	endDateStr := c.Query("endDate")
	pageStr := c.Query("page")
	pageSizeStr := c.Query("pageSize")
	queryStr := c.Query("q")

	filter := audit.QueryFilter{
		TenantID:   c.Get("X-Tenant-ID"),
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

func (h *Handler) RegisterRoutes(app *fiber.App) {
	api := app.Group("/api/v1/audit")
	api.Get("/events", h.QueryEvents)
	api.Get("/stats", h.GetStats)
}
