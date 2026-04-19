package platform

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/medfund/gateway/internal/config"
)

// Handler handles all /api/v1/platform/* routes. It aggregates data from
// backend services, performing real health checks and forwarding auth where needed.
type Handler struct {
	cfg    *config.Config
	client *http.Client
}

func NewHandler(cfg *config.Config) *Handler {
	return &Handler{
		cfg:    cfg,
		client: &http.Client{Timeout: 5 * time.Second},
	}
}

// Register mounts all platform routes on the given Fiber router group.
func (h *Handler) Register(router fiber.Router) {
	router.Get("/stats", h.getStats)
	router.Get("/health", h.getHealth)
	router.Get("/activity", h.getActivity)
	router.Get("/analytics/tenant-growth", h.getTenantGrowth)
	router.Get("/analytics/claims-distribution", h.getClaimsDistribution)
	router.Get("/analytics/claims-over-time", h.getClaimsOverTime)
	router.Get("/analytics/revenue-by-tenant", h.getRevenueByTenant)
	router.Get("/analytics/member-growth", h.getMemberGrowth)
	router.Get("/analytics/billing-over-time", h.getBillingOverTime)
	router.Get("/analytics/billing-payments-over-time", h.getBillingPaymentsOverTime)
	router.Get("/analytics/claim-payouts-over-time", h.getClaimPayoutsOverTime)
}

// ── Stats ─────────────────────────────────────────────────────────────────────

type statsResponse struct {
	TotalTenants   int     `json:"totalTenants"`
	ActiveUsers    int     `json:"activeUsers"`
	TotalClaims    int     `json:"totalClaims"`
	MonthlyRevenue float64 `json:"monthlyRevenue"`
	TenantGrowth   float64 `json:"tenantGrowth"`
	UserGrowth     float64 `json:"userGrowth"`
	ClaimsGrowth   float64 `json:"claimsGrowth"`
	RevenueGrowth  float64 `json:"revenueGrowth"`
}

func (h *Handler) getStats(c *fiber.Ctx) error {
	token := c.Cookies("access_token")

	type result struct {
		tenants      int
		staffUsers   int
		tenantMembers int
		claims       int
	}
	ch := make(chan result, 1)

	go func() {
		var wg sync.WaitGroup
		var tenants, staffUsers, tenantMembers, claims int

		wg.Add(3)
		go func() {
			defer wg.Done()
			tenants = h.fetchSingleInt(h.cfg.TenancyServiceURL+"/api/v1/platform/tenant-count", "totalTenants", token)
		}()
		go func() {
			defer wg.Done()
			staffUsers, tenantMembers = h.fetchUserStats(h.cfg.UserServiceURL+"/api/v1/platform/user-stats", token)
		}()
		go func() {
			defer wg.Done()
			claims = h.fetchClaimsStats(h.cfg.ClaimsServiceURL+"/api/v1/platform/claims-stats", token)
		}()

		wg.Wait()
		ch <- result{tenants, staffUsers, tenantMembers, claims}
	}()

	r := <-ch
	return c.JSON(statsResponse{
		TotalTenants:   r.tenants,
		ActiveUsers:    r.staffUsers + r.tenantMembers,
		TotalClaims:    r.claims,
		MonthlyRevenue: 0,
		TenantGrowth:   0,
		UserGrowth:     0,
		ClaimsGrowth:   0,
		RevenueGrowth:  0,
	})
}

// ── Health ────────────────────────────────────────────────────────────────────

type serviceHealth struct {
	Service     string `json:"service"`
	DisplayName string `json:"displayName"`
	Description string `json:"description"`
	Category    string `json:"category"`
	Status      string `json:"status"`
	Latency     int    `json:"latency"`
}

type serviceSpec struct {
	key         string
	url         string
	displayName string
	description string
	category    string
}

func (h *Handler) getHealth(c *fiber.Ctx) error {
	checks := []serviceSpec{
		{
			"tenancy-service", h.cfg.TenancyServiceURL + "/actuator/health",
			"Tenant Management", "Handles tenant onboarding, plans, schema provisioning and configuration", "core",
		},
		{
			"user-service", h.cfg.UserServiceURL + "/actuator/health",
			"User Service", "Manages members, providers, groups, staff users and Keycloak sync", "core",
		},
		{
			"claims-service", h.cfg.ClaimsServiceURL + "/actuator/health",
			"Claims Processing", "Handles claim submission, adjudication, pre-authorisations and tariffs", "core",
		},
		{
			"contributions-service", h.cfg.ContribServiceURL + "/actuator/health",
			"Contributions", "Manages schemes, member contributions, invoices and transactions", "core",
		},
		{
			"finance-service", h.cfg.FinanceServiceURL + "/actuator/health",
			"Finance", "Processes payments, payment runs, provider balances and reconciliations", "core",
		},
		{
			"notification-service", h.cfg.NotifServiceURL + "/health",
			"Notifications", "Delivers email, SMS and in-app notifications via Kafka events", "support",
		},
		{
			"audit-service", h.cfg.AuditServiceURL + "/health",
			"Audit Log", "Stores immutable audit trail for all entity mutations across the platform", "support",
		},
		{
			"file-service", h.cfg.FileServiceURL + "/health",
			"File Storage", "Generates pre-signed S3 upload/download URLs and handles document exports", "support",
		},
		{
			"ai-service", "http://localhost:8000/health",
			"AI & Analytics", "Provides AI-assisted adjudication, fraud detection, OCR and chatbot", "support",
		},
		{
			"live-dashboard", "http://localhost:4000/api/v1/dashboard/health",
			"Live Dashboard", "Real-time Phoenix/WebSocket service for live operational dashboards", "support",
		},
		{
			"chat-service", "http://localhost:4001/api/v1/chat/health",
			"Chat Service", "Internal messaging and support chat powered by Phoenix Channels", "support",
		},
	}

	type indexed struct {
		i int
		s serviceHealth
	}

	results := make([]serviceHealth, len(checks))
	ch := make(chan indexed, len(checks))
	var wg sync.WaitGroup

	for i, svc := range checks {
		wg.Add(1)
		go func(idx int, spec serviceSpec) {
			defer wg.Done()
			start := time.Now()
			resp, err := h.client.Get(spec.url)
			latency := int(time.Since(start).Milliseconds())
			status := "down"
			if err == nil {
				resp.Body.Close()
				switch {
				case resp.StatusCode == 200:
					status = "healthy"
				case resp.StatusCode < 500:
					status = "degraded"
				}
			}
			ch <- indexed{idx, serviceHealth{
				Service:     spec.key,
				DisplayName: spec.displayName,
				Description: spec.description,
				Category:    spec.category,
				Status:      status,
				Latency:     latency,
			}}
		}(i, svc)
	}

	wg.Wait()
	close(ch)
	for r := range ch {
		results[r.i] = r.s
	}

	return c.JSON(results)
}

// ── Activity ──────────────────────────────────────────────────────────────────

type activityItem struct {
	ID          string `json:"id"`
	Type        string `json:"type"`
	Description string `json:"description"`
	Actor       string `json:"actor"`
	Timestamp   string `json:"timestamp"`
	Severity    string `json:"severity"`
}

func (h *Handler) getActivity(c *fiber.Ctx) error {
	token := c.Cookies("access_token")
	url := h.cfg.AuditServiceURL + "/api/v1/audit/events?pageSize=10&page=1"

	body, err := h.fetchJSON(url, token)
	if err != nil {
		return c.JSON([]activityItem{})
	}

	var raw struct {
		Events []struct {
			ID         string `json:"id"`
			Action     string `json:"action"`
			EntityType string `json:"entityType"`
			EntityID   string `json:"entityId"`
			EntityName string `json:"entityName"`
			ActorID    string `json:"actorId"`
			ActorEmail string `json:"actorEmail"`
			Timestamp  string `json:"timestamp"`
		} `json:"events"`
	}
	if err := json.Unmarshal(body, &raw); err != nil {
		return c.JSON([]activityItem{})
	}

	items := make([]activityItem, 0, len(raw.Events))
	for _, e := range raw.Events {
		// Prefer human-readable names over UUIDs
		entity := e.EntityName
		if entity == "" {
			entity = e.EntityID
		}
		actor := e.ActorEmail
		if actor == "" {
			actor = e.ActorID
		}

		items = append(items, activityItem{
			ID:          e.ID,
			Type:        e.Action,
			Description: fmt.Sprintf("%s %s — %s", actionLabel(e.Action), entityTypeLabel(e.EntityType), entity),
			Actor:       actor,
			Timestamp:   e.Timestamp,
			Severity:    severityFor(e.Action),
		})
	}
	return c.JSON(items)
}

func actionLabel(action string) string {
	labels := map[string]string{
		"CREATE":               "Created",
		"UPDATE":               "Updated",
		"DELETE":               "Deleted",
		"LOGIN":                "Logged in",
		"LOGOUT":               "Logged out",
		"LOGIN_ERROR":          "Failed login",
		"RESET_PASSWORD":       "Reset password",
		"UPDATE_PASSWORD":      "Changed password",
		"VERIFY_EMAIL":         "Verified email",
		"SEND_VERIFY_EMAIL":    "Sent verification email",
		"SEND_RESET_PASSWORD":  "Sent password reset",
		"UPDATE_EMAIL":         "Updated email",
		"UPDATE_PROFILE":       "Updated profile",
		"REGISTER":             "Registered",
	}
	if l, ok := labels[action]; ok {
		return l
	}
	return action
}

func entityTypeLabel(entityType string) string {
	labels := map[string]string{
		"TENANT": "tenant",
		"USER":   "user",
		"CLAIM":  "claim",
		"AUTH":   "",
	}
	if l, ok := labels[entityType]; ok {
		return l
	}
	return strings.ToLower(entityType)
}

func severityFor(action string) string {
	switch action {
	case "DELETE", "LOGIN_ERROR", "RESET_PASSWORD_ERROR", "REGISTER_ERROR":
		return "warning"
	case "LOGIN", "REGISTER", "VERIFY_EMAIL":
		return "success"
	default:
		return "info"
	}
}

// ── Analytics ─────────────────────────────────────────────────────────────────

type growthPoint struct {
	Month string `json:"month"`
	Count int    `json:"count"`
}

type distributionItem struct {
	Status string `json:"status"`
	Count  int    `json:"count"`
	Color  string `json:"color"`
}

// getTenantGrowth fetches all tenants from the tenancy service and groups
// them by month of creation, returning a 12-month time series.
func (h *Handler) getTenantGrowth(c *fiber.Ctx) error {
	token := c.Cookies("access_token")

	body, err := h.fetchJSON(h.cfg.TenancyServiceURL+"/api/v1/tenants?size=1000&page=1", token)
	if err != nil {
		return c.JSON(zeroMonthSeries())
	}

	var page struct {
		Content []struct {
			CreatedAt string `json:"createdAt"`
		} `json:"content"`
	}
	if err := json.Unmarshal(body, &page); err != nil {
		return c.JSON(zeroMonthSeries())
	}

	// Count tenants created in each calendar month of the current year.
	now := time.Now()
	counts := make(map[string]int) // "Jan", "Feb", ...
	for _, t := range page.Content {
		ts, err := time.Parse(time.RFC3339Nano, t.CreatedAt)
		if err != nil {
			// Try without nanoseconds
			ts, err = time.Parse(time.RFC3339, t.CreatedAt)
			if err != nil {
				continue
			}
		}
		// Only include the last 12 months
		if now.Sub(ts) <= 365*24*time.Hour {
			counts[ts.Format("Jan")]++
		}
	}

	months := []string{"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"}
	out := make([]growthPoint, len(months))
	for i, m := range months {
		out[i] = growthPoint{Month: m, Count: counts[m]}
	}
	return c.JSON(out)
}

// getMemberGrowth fetches real member growth data from the user-service.
func (h *Handler) getMemberGrowth(c *fiber.Ctx) error {
	token := c.Cookies("access_token")

	body, err := h.fetchJSON(h.cfg.UserServiceURL+"/api/v1/platform/member-growth", token)
	if err != nil {
		return c.JSON(zeroMonthSeries())
	}

	var points []growthPoint
	if err := json.Unmarshal(body, &points); err != nil {
		return c.JSON(zeroMonthSeries())
	}
	if len(points) == 0 {
		return c.JSON(zeroMonthSeries())
	}
	return c.JSON(points)
}

// getClaimsDistribution fetches the real claim status breakdown from the claims service.
func (h *Handler) getClaimsDistribution(c *fiber.Ctx) error {
	token := c.Cookies("access_token")

	body, err := h.fetchJSON(h.cfg.ClaimsServiceURL+"/api/v1/platform/claims-distribution", token)
	if err != nil {
		return c.JSON(placeholderDistribution())
	}

	var items []distributionItem
	if err := json.Unmarshal(body, &items); err != nil {
		return c.JSON(placeholderDistribution())
	}
	return c.JSON(items)
}

func (h *Handler) getClaimsOverTime(c *fiber.Ctx) error {
	return c.JSON(zeroMonthSeries())
}

func (h *Handler) getBillingOverTime(c *fiber.Ctx) error {
	return c.JSON(zeroMonthSeries())
}

func (h *Handler) getBillingPaymentsOverTime(c *fiber.Ctx) error {
	return c.JSON(zeroMonthSeries())
}

func (h *Handler) getClaimPayoutsOverTime(c *fiber.Ctx) error {
	return c.JSON(zeroMonthSeries())
}

func (h *Handler) getRevenueByTenant(c *fiber.Ctx) error {
	return c.JSON([]fiber.Map{})
}

// zeroMonthSeries returns a 12-element slice with all counts set to 0.
// Used as a fallback when real data cannot be fetched.
func zeroMonthSeries() []growthPoint {
	months := []string{"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"}
	out := make([]growthPoint, len(months))
	for i, m := range months {
		out[i] = growthPoint{Month: m, Count: 0}
	}
	return out
}

// placeholderDistribution returns equal-weight placeholder slices when real data
// is unavailable — the dashboard renders these as equal-sized pie segments.
func placeholderDistribution() []distributionItem {
	return []distributionItem{
		{Status: "approved", Count: 0, Color: "#2EC4B6"},
		{Status: "pending", Count: 0, Color: "#FF9F1C"},
		{Status: "rejected", Count: 0, Color: "#E71D36"},
		{Status: "in_adjudication", Count: 0, Color: "#00B4D8"},
		{Status: "submitted", Count: 0, Color: "#90E0EF"},
	}
}

// ── Helpers ───────────────────────────────────────────────────────────────────

// fetchJSON GETs a URL with an optional Bearer token and returns the raw body.
func (h *Handler) fetchJSON(url, token string) ([]byte, error) {
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := h.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("upstream %d", resp.StatusCode)
	}
	return io.ReadAll(resp.Body)
}

// fetchArrayLen calls a list endpoint and returns the length of the JSON array.
func (h *Handler) fetchArrayLen(url, token string) int {
	body, err := h.fetchJSON(url, token)
	if err != nil {
		return 0
	}
	var items []json.RawMessage
	if err := json.Unmarshal(body, &items); err != nil {
		return 0
	}
	return len(items)
}

// fetchPagedCount calls a paginated endpoint and returns the totalCount field.
// Used for endpoints that return { content: [...], totalCount: N, ... } instead of a plain array.
func (h *Handler) fetchPagedCount(url, token string) int {
	body, err := h.fetchJSON(url, token)
	if err != nil {
		return 0
	}
	var page struct {
		TotalCount int `json:"totalCount"`
	}
	if err := json.Unmarshal(body, &page); err != nil {
		return 0
	}
	return page.TotalCount
}

// fetchSingleInt calls an endpoint that returns a JSON object and reads one integer field by name.
func (h *Handler) fetchSingleInt(url, field, token string) int {
	body, err := h.fetchJSON(url, token)
	if err != nil {
		return 0
	}
	var obj map[string]json.Number
	if err := json.Unmarshal(body, &obj); err != nil {
		return 0
	}
	if v, ok := obj[field]; ok {
		n, _ := v.Int64()
		return int(n)
	}
	return 0
}

// fetchUserStats calls the user-service platform/user-stats endpoint and returns
// (staffUsers, tenantMembers).
func (h *Handler) fetchUserStats(url, token string) (int, int) {
	body, err := h.fetchJSON(url, token)
	if err != nil {
		return 0, 0
	}
	var obj map[string]json.Number
	if err := json.Unmarshal(body, &obj); err != nil {
		return 0, 0
	}
	toInt := func(key string) int {
		if v, ok := obj[key]; ok {
			n, _ := v.Int64()
			return int(n)
		}
		return 0
	}
	return toInt("staffUsers"), toInt("tenantMembers")
}

// fetchClaimsStats calls the claims-service platform/claims-stats endpoint.
func (h *Handler) fetchClaimsStats(url, token string) int {
	return h.fetchSingleInt(url, "totalClaims", token)
}
