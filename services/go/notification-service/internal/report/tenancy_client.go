package report

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

// Recipient mirrors the wire shape of TenantReportScheduleRecipientResponse
// on the Java side — only the fields the dispatcher reads. Adding a field
// to the Java DTO does not break this consumer.
type Recipient struct {
	ID               string `json:"id"`
	ScheduleID       string `json:"scheduleId"`
	Email            string `json:"email"`
	DisplayName      string `json:"displayName"`
	IsActive         bool   `json:"isActive"`
	UnsubscribeToken string `json:"unsubscribeToken"`
}

// RecipientLookup is the surface the dispatcher depends on so tests can
// inject a stub without spinning up an HTTP server.
type RecipientLookup interface {
	ActiveFor(ctx context.Context, tenantID, scheduleID string) ([]Recipient, error)
	TenantContactEmail(ctx context.Context, tenantID string) (string, error)
}

// HTTPTenancyClient reads the tenancy-service endpoints:
//
//	GET {base}/api/v1/tenants/{tenantId}/report-schedules/{scheduleId}/recipients/active
//	GET {base}/api/v1/tenants/{tenantId}                                (for contactEmail)
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

func (c *HTTPTenancyClient) ActiveFor(ctx context.Context, tenantID, scheduleID string) ([]Recipient, error) {
	if c == nil || c.BaseURL == "" {
		return nil, fmt.Errorf("tenancy client not configured")
	}
	endpoint := fmt.Sprintf("%s/api/v1/tenants/%s/report-schedules/%s/recipients/active",
		c.BaseURL, url.PathEscape(tenantID), url.PathEscape(scheduleID))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
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

// tenantContactPayload is the projection of TenantResponse we need.
// Extra fields are ignored by the JSON decoder.
type tenantContactPayload struct {
	ContactEmail string `json:"contactEmail"`
	Name         string `json:"name"`
	Slug         string `json:"slug"`
	Timezone     string `json:"timezone"`
}

func (c *HTTPTenancyClient) TenantContactEmail(ctx context.Context, tenantID string) (string, error) {
	if c == nil || c.BaseURL == "" {
		return "", fmt.Errorf("tenancy client not configured")
	}
	endpoint := fmt.Sprintf("%s/api/v1/tenants/%s", c.BaseURL, url.PathEscape(tenantID))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return "", fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	resp, err := c.Client.Do(req)
	if err != nil {
		return "", fmt.Errorf("call tenancy-service: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return "", fmt.Errorf("tenancy-service returned %d: %s", resp.StatusCode, string(body))
	}
	var payload tenantContactPayload
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return "", fmt.Errorf("decode tenant: %w", err)
	}
	return payload.ContactEmail, nil
}

// TenantMetadata carries the tenant fields the delivery templates need.
type TenantMetadata struct {
	Name     string
	Slug     string
	Timezone string
}

// TenantMetadataLookup is a small helper the dispatcher uses to enrich
// the email subject/body with the tenant display name. Failing open
// (returning zero-value TenantMetadata with no error) is acceptable —
// the templates degrade to using the tenantId as the display name.
type TenantMetadataLookup interface {
	Get(ctx context.Context, tenantID string) TenantMetadata
}

// GetMetadata satisfies TenantMetadataLookup on the HTTP client — same
// tenant GET as TenantContactEmail but returns the full struct. Errors
// degrade to zero-value so a temporary tenancy-service blip doesn't
// stop delivery.
func (c *HTTPTenancyClient) Get(ctx context.Context, tenantID string) TenantMetadata {
	if c == nil || c.BaseURL == "" {
		return TenantMetadata{}
	}
	endpoint := fmt.Sprintf("%s/api/v1/tenants/%s", c.BaseURL, url.PathEscape(tenantID))
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return TenantMetadata{}
	}
	req.Header.Set("Accept", "application/json")
	resp, err := c.Client.Do(req)
	if err != nil {
		return TenantMetadata{}
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return TenantMetadata{}
	}
	var payload tenantContactPayload
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return TenantMetadata{}
	}
	return TenantMetadata{
		Name:     payload.Name,
		Slug:     payload.Slug,
		Timezone: payload.Timezone,
	}
}
