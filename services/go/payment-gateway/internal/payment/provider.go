package payment

import "time"

type Status string

const (
	StatusPending   Status = "pending"
	StatusCompleted Status = "completed"
	StatusFailed    Status = "failed"
	StatusRefunded  Status = "refunded"
)

type InitiateRequest struct {
	TenantID       string  `json:"tenantId"`
	Amount         float64 `json:"amount"`
	Currency       string  `json:"currency"`
	Method         string  `json:"method"` // ecocash, card, bank_transfer
	Reference      string  `json:"reference"`
	Description    string  `json:"description"`
	ReturnURL      string  `json:"returnUrl"`
	IdempotencyKey string  `json:"idempotencyKey"`
	// V075 — outbound-only. Which tenant bank account the payout debits;
	// carried through so a real provider integration can route.
	BankAccountID string `json:"bankAccountId,omitempty"`
	// V075 — "inbound" (default) or "outbound". Recorded on the ledger
	// row so the UI can distinguish receipts from disbursements.
	Direction string `json:"direction,omitempty"`
}

type InitiateResponse struct {
	TransactionID string `json:"transactionId"`
	RedirectURL   string `json:"redirectUrl,omitempty"`
	Status        Status `json:"status"`
	ProviderRef   string `json:"providerRef"`
}

type Transaction struct {
	ID             string    `json:"id"`
	TenantID       string    `json:"tenantId"`
	Amount         float64   `json:"amount"`
	Currency       string    `json:"currency"`
	Method         string    `json:"method"`
	Status         Status    `json:"status"`
	Reference      string    `json:"reference"`
	ProviderRef    string    `json:"providerRef"`
	Provider       string    `json:"provider"`
	IdempotencyKey string    `json:"idempotencyKey"`
	Direction      string    `json:"direction"` // inbound, outbound
	CreatedAt      time.Time `json:"createdAt"`
	UpdatedAt      time.Time `json:"updatedAt"`
}

// Provider interface — each payment provider implements this
type Provider interface {
	Name() string
	Initiate(req InitiateRequest) (*InitiateResponse, error)
	CheckStatus(providerRef string) (Status, error)
	VerifyWebhook(payload []byte, signature string) bool
}
