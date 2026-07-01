package contributions

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// Client is a thin HTTP client for contributions-service internal
// render-payload endpoint. Used by the PDF renderer to fetch the
// full statement + per-contribution rows in one round-trip so the
// PDF matches the Angular statement page 1:1.
type Client struct {
	baseURL string
	http    *http.Client
}

// New creates a Client bound to the given contributions-service base
// URL (typically http://contributions-service:8084 in docker or
// http://localhost:8084 locally). A short 5-second timeout matches the
// wkhtmltopdf render budget — the renderer must finish before the
// consumer's Kafka rebalance interval or the message re-delivers.
func New(baseURL string) *Client {
	return &Client{
		baseURL: baseURL,
		http:    &http.Client{Timeout: 5 * time.Second},
	}
}

// FetchRenderPayload calls GET /api/v1/internal/invoices/{id}/render-payload
// with the tenant scoped via the X-Tenant-ID header (contributions-service's
// TenantWebFilter reads it). Non-2xx responses return an error including
// the body so the caller can log something diagnostic.
func (c *Client) FetchRenderPayload(ctx context.Context, tenantID, invoiceID string) (*RenderPayload, error) {
	if c.baseURL == "" {
		return nil, fmt.Errorf("contributions base URL not configured")
	}
	if tenantID == "" || invoiceID == "" {
		return nil, fmt.Errorf("tenantID and invoiceID are required")
	}
	url := fmt.Sprintf("%s/api/v1/internal/invoices/%s/render-payload", c.baseURL, invoiceID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("X-Tenant-ID", tenantID)
	req.Header.Set("Accept", "application/json")

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("call contributions-service: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("contributions-service %d: %s", resp.StatusCode, string(body))
	}
	var out RenderPayload
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, fmt.Errorf("decode payload: %w", err)
	}
	return &out, nil
}

// ─── Wire types ────────────────────────────────────────────────────────
//
// Mirror the Java DTOs on contributions-service:
//   InvoiceRenderPayload → RenderPayload
//   InvoiceResponse       → Invoice
//   StatementResponse     → Statement / StatementHeader / StatementLine
//   InvoiceContributionRow → ContributionRow

type RenderPayload struct {
	Invoice       Invoice           `json:"invoice"`
	Statement     Statement         `json:"statement"`
	Contributions []ContributionRow `json:"contributions"`
}

type Invoice struct {
	ID                  string      `json:"id"`
	InvoiceNumber       string      `json:"invoiceNumber"`
	SchemeID            string      `json:"schemeId,omitempty"`
	GroupID             string      `json:"groupId,omitempty"`
	MemberID            string      `json:"memberId,omitempty"`
	TotalAmount         json.Number `json:"totalAmount"`
	CurrencyCode        string      `json:"currencyCode"`
	PeriodStart         string      `json:"periodStart"`
	PeriodEnd           string      `json:"periodEnd"`
	Status              string      `json:"status"`
	DueDate             string      `json:"dueDate"`
	IssuedAt            string      `json:"issuedAt"`
	CommittedAt         string      `json:"committedAt,omitempty"`
	OpeningBalance      json.Number `json:"openingBalance,omitempty"`
	ClosingBalance      json.Number `json:"closingBalance,omitempty"`
	PaymentsInWindow    json.Number `json:"paymentsInWindow,omitempty"`
	AdjustmentsInWindow json.Number `json:"adjustmentsInWindow,omitempty"`
}

type Statement struct {
	Header StatementHeader `json:"header"`
	Lines  []StatementLine `json:"lines"`
}

type StatementHeader struct {
	TargetType     string      `json:"targetType"`
	TargetID       string      `json:"targetId"`
	TargetName     string      `json:"targetName"`
	TargetCode     string      `json:"targetCode"`
	PeriodStart    string      `json:"periodStart"`
	PeriodEnd      string      `json:"periodEnd"`
	CurrencyCode   string      `json:"currencyCode"`
	OpeningBalance json.Number `json:"openingBalance,omitempty"`
	ClosingBalance json.Number `json:"closingBalance,omitempty"`
	TotalCharges   json.Number `json:"totalCharges,omitempty"`
	TotalPayments  json.Number `json:"totalPayments,omitempty"`
}

type StatementLine struct {
	Date           string      `json:"date"`
	Type           string      `json:"type"`
	Description    string      `json:"description"`
	Reference      string      `json:"reference"`
	Debit          json.Number `json:"debit,omitempty"`
	Credit         json.Number `json:"credit,omitempty"`
	RunningBalance json.Number `json:"runningBalance,omitempty"`
	SourceID       string      `json:"sourceId,omitempty"`
}

type ContributionRow struct {
	ContributionID string      `json:"contributionId"`
	MemberNumber   string      `json:"memberNumber"`
	MemberName     string      `json:"memberName"`
	PersonType     string      `json:"personType"`
	DependantName  string      `json:"dependantName,omitempty"`
	SchemeName     string      `json:"schemeName"`
	InsuranceLine  string      `json:"insuranceLine"`
	AgeBand        string      `json:"ageBand,omitempty"`
	Amount         json.Number `json:"amount"`
	CurrencyCode   string      `json:"currencyCode"`
}
