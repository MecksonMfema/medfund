package regulatory

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

// Recipient mirrors the wire shape of TenantRegulatoryRecipientResponse
// on the Java side — only the fields the dispatcher reads. Adding a
// field to the Java DTO does not break this consumer.
type Recipient struct {
	ID                    string   `json:"id"`
	TenantID              string   `json:"tenantId"`
	Email                 string   `json:"email"`
	DisplayName           string   `json:"displayName"`
	SubscribedEventTiers  []string `json:"subscribedEventTiers"`
	IsActive              bool     `json:"isActive"`
}

// SubscribedTo returns true when the recipient is opted in to the given
// event tier. Empty slice defaults to "no tiers subscribed" — a
// recipient with an empty subscription list receives nothing. The
// Java-side default fills all four tiers on insert so this only bites
// after a manual UPDATE.
func (r Recipient) SubscribedTo(tier string) bool {
	for _, t := range r.SubscribedEventTiers {
		if t == tier {
			return true
		}
	}
	return false
}

// RecipientLookup is the interface the dispatcher depends on so tests
// can inject a stub without spinning up an HTTP server.
type RecipientLookup interface {
	ActiveFor(ctx context.Context, tenantID string) ([]Recipient, error)
}

// HTTPTenancyClient reads the /active endpoint served by
// TenantRegulatoryRecipientController on the tenancy-service:
//
//	GET {baseURL}/api/v1/tenants/{tenantId}/regulatory-recipients/active
//
// Only currently-active rows come back; the per-tier subscription
// filter is applied by the dispatcher so the filter logic stays close
// to the fan-out.
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

func (c *HTTPTenancyClient) ActiveFor(ctx context.Context, tenantID string) ([]Recipient, error) {
	if c == nil || c.BaseURL == "" {
		return nil, fmt.Errorf("tenancy client not configured")
	}
	u := fmt.Sprintf("%s/api/v1/tenants/%s/regulatory-recipients/active",
		c.BaseURL, url.PathEscape(tenantID))
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

	var recipients []Recipient
	if err := json.NewDecoder(resp.Body).Decode(&recipients); err != nil {
		return nil, fmt.Errorf("decode recipients: %w", err)
	}
	return recipients, nil
}
