// Package tenancy is the market-data-service's read path into
// tenancy-service. Called once per fetch tick to build the schedule
// of (tenant, currency, source) tuples to iterate the adapters over.
package tenancy

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// MarketDataConfig mirrors the TenantMarketDataConfigResponse wire
// shape on the Java side. Only the fields the daemon needs are
// decoded — adding a field on the Java DTO does not break this
// consumer.
type MarketDataConfig struct {
	ID               string `json:"id"`
	TenantID         string `json:"tenantId"`
	Currency         string `json:"currency"`
	Source           string `json:"source"`
	AutoFetchEnabled bool   `json:"autoFetchEnabled"`
}

// ConfigLookup is the daemon's dependency on tenancy-service. Tests
// substitute a stub returning canned rows without needing an HTTP
// server.
type ConfigLookup interface {
	ListEnabled(ctx context.Context) ([]MarketDataConfig, error)
}

// HTTPClient calls tenancy-service's internal endpoint:
//
//	GET {baseURL}/internal/v1/market-data-config/enabled
//
// The endpoint is exposed on the service's internal network only —
// permitAll'd in SecurityConfig for the /internal/** prefix. In a
// service-mesh world this would carry a per-service identity; that
// wiring is deferred beyond Phase 24.
type HTTPClient struct {
	BaseURL string
	Client  *http.Client
}

// NewHTTPClient wraps a base URL with a 5s HTTP client. Trailing slash
// on baseURL is trimmed for consistency with the notification-service
// tenancy client.
func NewHTTPClient(baseURL string) *HTTPClient {
	return &HTTPClient{
		BaseURL: strings.TrimRight(baseURL, "/"),
		Client:  &http.Client{Timeout: 5 * time.Second},
	}
}

// ListEnabled returns every currently enabled (tenant, currency, source)
// tuple across all tenants. A nil / zero-length slice is a valid empty
// state — the caller treats it as a no-op tick.
func (c *HTTPClient) ListEnabled(ctx context.Context) ([]MarketDataConfig, error) {
	if c == nil || c.BaseURL == "" {
		return nil, fmt.Errorf("tenancy client not configured")
	}
	url := c.BaseURL + "/internal/v1/market-data-config/enabled"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("build tenancy request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	resp, err := c.Client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("call tenancy-service: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return nil, fmt.Errorf("tenancy-service returned %d: %s", resp.StatusCode, string(body))
	}
	var out []MarketDataConfig
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("decode configs: %w", err)
	}
	return out, nil
}
