package handler

import (
	"encoding/json"
	"io"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gofiber/fiber/v2"
	"github.com/medfund/payment-gateway/internal/payment"
)

func setupApp() (*fiber.App, *payment.Ledger) {
	app := fiber.New()
	provider := payment.NewMockProvider()
	ledger := payment.NewLedger()
	h := New(provider, ledger)
	h.RegisterRoutes(app)
	return app, ledger
}

func TestInitiatePayment_ValidRequest_Returns201(t *testing.T) {
	app, _ := setupApp()
	body := strings.NewReader(`{"amount":100,"currency":"USD","method":"card","reference":"REF-1"}`)
	req := httptest.NewRequest("POST", "/api/v1/pay/initiate", body)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-ID", "test-tenant")

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 201 {
		t.Fatalf("expected 201, got %d", resp.StatusCode)
	}
}

func TestInitiatePayment_InvalidBody_Returns400(t *testing.T) {
	app, _ := setupApp()
	body := strings.NewReader("not json")
	req := httptest.NewRequest("POST", "/api/v1/pay/initiate", body)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 400 {
		t.Fatalf("expected 400, got %d", resp.StatusCode)
	}
}

func TestInitiatePayment_ResponseContainsID(t *testing.T) {
	app, _ := setupApp()
	body := strings.NewReader(`{"amount":250,"currency":"ZWL","method":"ecocash","reference":"REF-3"}`)
	req := httptest.NewRequest("POST", "/api/v1/pay/initiate", body)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-ID", "test-tenant")

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}

	respBody, _ := io.ReadAll(resp.Body)
	var txn map[string]interface{}
	if err := json.Unmarshal(respBody, &txn); err != nil {
		t.Fatalf("failed to parse response: %v", err)
	}
	if txn["id"] == nil || txn["id"] == "" {
		t.Fatal("expected response to contain an id")
	}
}

func TestInitiatePayment_OutboundDirection_RecordedOnLedger(t *testing.T) {
	app, ledger := setupApp()
	body := strings.NewReader(`{"amount":100,"currency":"USD","method":"bank_transfer","reference":"REF-OUT","direction":"outbound","bankAccountId":"bank-1"}`)
	req := httptest.NewRequest("POST", "/api/v1/pay/initiate", body)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-ID", "tenant-a")

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 201 {
		t.Fatalf("expected 201, got %d", resp.StatusCode)
	}

	txns := ledger.ListByTenant("tenant-a")
	if len(txns) != 1 {
		t.Fatalf("expected 1 recorded transaction, got %d", len(txns))
	}
	if txns[0].Direction != "outbound" {
		t.Fatalf("expected direction=outbound, got %q", txns[0].Direction)
	}
}

func TestInitiatePayment_Idempotency_ReturnsSameTransaction(t *testing.T) {
	app, _ := setupApp()

	payload := `{"amount":100,"currency":"USD","method":"card","reference":"REF-IDEM","idempotencyKey":"idem-key-1"}`

	// First request
	req1 := httptest.NewRequest("POST", "/api/v1/pay/initiate", strings.NewReader(payload))
	req1.Header.Set("Content-Type", "application/json")
	req1.Header.Set("X-Tenant-ID", "test-tenant")
	resp1, err := app.Test(req1)
	if err != nil {
		t.Fatal(err)
	}
	body1, _ := io.ReadAll(resp1.Body)
	var txn1 map[string]interface{}
	json.Unmarshal(body1, &txn1)

	// Second request with same idempotency key
	req2 := httptest.NewRequest("POST", "/api/v1/pay/initiate", strings.NewReader(payload))
	req2.Header.Set("Content-Type", "application/json")
	req2.Header.Set("X-Tenant-ID", "test-tenant")
	resp2, err := app.Test(req2)
	if err != nil {
		t.Fatal(err)
	}
	body2, _ := io.ReadAll(resp2.Body)
	var txn2 map[string]interface{}
	json.Unmarshal(body2, &txn2)

	// Should return the same transaction ID
	if txn1["id"] != txn2["id"] {
		t.Fatalf("expected same transaction ID for idempotent requests, got %v and %v", txn1["id"], txn2["id"])
	}
}

func TestGetTransaction_NotFound_Returns404(t *testing.T) {
	app, _ := setupApp()
	req := httptest.NewRequest("GET", "/api/v1/pay/transactions/nonexistent-id", nil)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 404 {
		t.Fatalf("expected 404, got %d", resp.StatusCode)
	}
}

func TestInitiateAndGetTransaction(t *testing.T) {
	app, _ := setupApp()

	// Initiate a payment
	body := strings.NewReader(`{"amount":50,"currency":"USD","method":"ecocash","reference":"REF-2"}`)
	req := httptest.NewRequest("POST", "/api/v1/pay/initiate", body)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-ID", "test-tenant")
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}

	respBody, _ := io.ReadAll(resp.Body)
	var txn map[string]interface{}
	json.Unmarshal(respBody, &txn)
	txnID, ok := txn["id"].(string)
	if !ok || txnID == "" {
		t.Fatal("expected transaction ID in response")
	}

	// Get by ID
	req2 := httptest.NewRequest("GET", "/api/v1/pay/transactions/"+txnID, nil)
	resp2, err := app.Test(req2)
	if err != nil {
		t.Fatal(err)
	}
	if resp2.StatusCode != 200 {
		t.Fatalf("expected 200 for existing transaction, got %d", resp2.StatusCode)
	}
}

func TestListTransactions_Empty_Returns200(t *testing.T) {
	app, _ := setupApp()
	req := httptest.NewRequest("GET", "/api/v1/pay/transactions", nil)
	req.Header.Set("X-Tenant-ID", "test-tenant")
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
}

func TestListTransactions_AfterInitiate_ReturnsTransaction(t *testing.T) {
	app, _ := setupApp()

	// Initiate a payment for tenant-a
	body := strings.NewReader(`{"amount":100,"currency":"USD","method":"card","reference":"REF-A"}`)
	req := httptest.NewRequest("POST", "/api/v1/pay/initiate", body)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Tenant-ID", "tenant-a")
	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 201 {
		t.Fatalf("expected 201, got %d", resp.StatusCode)
	}

	// List for tenant-a should contain the transaction
	req2 := httptest.NewRequest("GET", "/api/v1/pay/transactions", nil)
	req2.Header.Set("X-Tenant-ID", "tenant-a")
	resp2, err := app.Test(req2)
	if err != nil {
		t.Fatal(err)
	}
	if resp2.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp2.StatusCode)
	}

	respBody, _ := io.ReadAll(resp2.Body)
	var result map[string]interface{}
	json.Unmarshal(respBody, &result)
	txns, _ := result["transactions"].([]interface{})
	if len(txns) == 0 {
		t.Fatal("expected at least 1 transaction for tenant-a after initiation")
	}
}

func TestWebhook_ValidSignature_Returns200(t *testing.T) {
	app, _ := setupApp()
	body := strings.NewReader(`{"event":"payment.completed","transactionId":"txn-123"}`)
	req := httptest.NewRequest("POST", "/api/v1/pay/webhook", body)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Webhook-Signature", "test-sig")

	resp, err := app.Test(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
}
