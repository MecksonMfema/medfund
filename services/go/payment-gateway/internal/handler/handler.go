package handler

import (
	"github.com/gofiber/fiber/v2"
	"github.com/medfund/payment-gateway/internal/payment"
)

type Handler struct {
	provider payment.Provider
	ledger   *payment.Ledger
}

func New(provider payment.Provider, ledger *payment.Ledger) *Handler {
	return &Handler{provider: provider, ledger: ledger}
}

func (h *Handler) InitiatePayment(c *fiber.Ctx) error {
	var req payment.InitiateRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{"error": "invalid request"})
	}
	req.TenantID = c.Get("X-Tenant-ID")

	// Idempotency check
	if req.IdempotencyKey != "" {
		if existing, found := h.ledger.CheckIdempotency(req.IdempotencyKey); found {
			return c.JSON(existing)
		}
	}

	resp, err := h.provider.Initiate(req)
	if err != nil {
		return c.Status(fiber.StatusBadGateway).JSON(fiber.Map{"error": "payment provider error: " + err.Error()})
	}

	direction := req.Direction
	if direction == "" {
		direction = "inbound"
	}
	txn := h.ledger.Record(req, resp, h.provider.Name(), direction)
	return c.Status(fiber.StatusCreated).JSON(txn)
}

func (h *Handler) GetTransaction(c *fiber.Ctx) error {
	id := c.Params("id")
	txn, found := h.ledger.GetByID(id)
	if !found {
		return c.Status(fiber.StatusNotFound).JSON(fiber.Map{"error": "transaction not found"})
	}
	return c.JSON(txn)
}

func (h *Handler) ListTransactions(c *fiber.Ctx) error {
	tenantID := c.Get("X-Tenant-ID")
	txns := h.ledger.ListByTenant(tenantID)
	return c.JSON(fiber.Map{"transactions": txns})
}

func (h *Handler) Webhook(c *fiber.Ctx) error {
	signature := c.Get("X-Webhook-Signature")
	body := c.Body()

	if !h.provider.VerifyWebhook(body, signature) {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{"error": "invalid webhook signature"})
	}

	// Process webhook payload — provider-specific
	return c.JSON(fiber.Map{"status": "received"})
}

func (h *Handler) RegisterRoutes(app *fiber.App) {
	api := app.Group("/api/v1/pay")
	api.Post("/initiate", h.InitiatePayment)
	api.Get("/transactions", h.ListTransactions)
	api.Get("/transactions/:id", h.GetTransaction)
	api.Post("/webhook", h.Webhook)
}
