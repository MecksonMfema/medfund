package ifrs17

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// NotificationConfig mirrors the wire shape of
// TenantIfrs17NotificationConfigResponse on the Java side — only the
// fields the dispatcher needs are decoded here. Adding a field to the
// Java DTO does not break this consumer.
type NotificationConfig struct {
	ID              string `json:"id"`
	TenantID        string `json:"tenantId"`
	EventType       string `json:"eventType"`
	DeliveryMethod  string `json:"deliveryMethod"`
	Recipient       string `json:"recipient"`
	ThrottleMinutes int    `json:"throttleMinutes"`
	IsActive        bool   `json:"isActive"`
}

// ConfigLookup is the interface the dispatcher depends on so tests
// can inject a stub without spinning up an HTTP server.
type ConfigLookup interface {
	ActiveFor(ctx context.Context, tenantID, eventType string) ([]NotificationConfig, error)
}

// HTTPTenancyClient reads the /active endpoint served by
// TenantIfrs17NotificationConfigController on the tenancy-service:
//   GET {baseURL}/api/v1/tenants/{tenantId}/ifrs17-notification-config/active?eventType=X
//
// The tenancy-service response concatenates the concrete-eventType
// rows with the wildcard "ALL" rows, so the dispatcher doesn't need
// to fan out its own second lookup.
type HTTPTenancyClient struct {
	BaseURL string
	Client  *http.Client
}

func NewHTTPTenancyClient(baseURL string) *HTTPTenancyClient {
	return &HTTPTenancyClient{
		BaseURL: strings.TrimRight(baseURL, "/"),
		Client:  &http.Client{Timeout: 5 * time.Second},
	}
}

// ActiveFor returns every active recipient for the given (tenant,
// eventType). An empty slice + nil error is a clean "no recipients
// configured" — the dispatcher treats it as a no-op, not a failure.
func (c *HTTPTenancyClient) ActiveFor(ctx context.Context, tenantID, eventType string) ([]NotificationConfig, error) {
	if c == nil || c.BaseURL == "" {
		return nil, fmt.Errorf("tenancy client not configured")
	}
	u := fmt.Sprintf("%s/api/v1/tenants/%s/ifrs17-notification-config/active?eventType=%s",
		c.BaseURL, url.PathEscape(tenantID), url.QueryEscape(eventType))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, fmt.Errorf("build request: %w", err)
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

	var configs []NotificationConfig
	if err := json.NewDecoder(resp.Body).Decode(&configs); err != nil {
		return nil, fmt.Errorf("decode configs: %w", err)
	}
	return configs, nil
}
