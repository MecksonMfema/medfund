package routes

import (
	"net/http/httptest"
	"testing"

	"github.com/gofiber/fiber/v2"
	"github.com/medfund/gateway/internal/config"
)

// Phase 15 §23 rename Phase C cleanup: the Phase 22 GET-only 301 redirect
// handlers on /api/v1/reports/actuarial/jobs/* have been deleted. Requests
// to that path (both poll + export) now fall through to the
// /api/v1/reports/actuarial/* wildcard proxy, which still routes to
// finance-service's Phase-14-native ActuarialReportController (retained per
// the Phase 21 deviation "actuarial precedent puts its export at
// /api/v1/reports/actuarial/jobs/{jobId}/export.xlsx"). Angular's new
// polling service hits the canonical /api/v1/reports/jobs/* URL directly;
// the actuarial-specific URL stays as a legacy-but-not-deprecated proxy.
func TestLegacyActuarialJobsPollUrlIsNoLongerRedirected(t *testing.T) {
	app := newAppUnderTest()

	req := httptest.NewRequest("GET", "/api/v1/reports/actuarial/jobs/abc-123", nil)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if resp.StatusCode == fiber.StatusMovedPermanently {
		t.Fatalf("legacy actuarial jobs URL must not 301 (§23 removed the redirect); "+
			"got Location=%q", resp.Header.Get("Location"))
	}
}

func TestLegacyActuarialJobsExportUrlIsNoLongerRedirected(t *testing.T) {
	app := newAppUnderTest()

	req := httptest.NewRequest("GET",
		"/api/v1/reports/actuarial/jobs/abc-123/export.xlsx", nil)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if resp.StatusCode == fiber.StatusMovedPermanently {
		t.Fatalf("legacy actuarial jobs export URL must not 301 (§23 removed the redirect); "+
			"got Location=%q", resp.Header.Get("Location"))
	}
}

// Submit endpoints on /reports/actuarial/{ibnr,loss-triangle,…} still hit
// the wildcard proxy — same behaviour as before §22 introduced the redirect
// handlers, and after §23 deleted them.
func TestActuarialSubmitEndpointsAreProxied(t *testing.T) {
	app := newAppUnderTest()

	req := httptest.NewRequest("POST", "/api/v1/reports/actuarial/ibnr", nil)
	resp, err := app.Test(req)
	if err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if resp.StatusCode == fiber.StatusMovedPermanently {
		t.Fatalf("submit endpoint must not 301; got redirect to %q",
			resp.Header.Get("Location"))
	}
	if resp.StatusCode == fiber.StatusNotFound {
		t.Fatalf("submit endpoint must not 404 (wildcard proxy expected); got %d",
			resp.StatusCode)
	}
}

func newAppUnderTest() *fiber.App {
	app := fiber.New(fiber.Config{DisableStartupMessage: true})
	cfg := &config.Config{
		FinanceServiceURL: "http://finance:0",
		TenancyServiceURL: "http://tenancy:0",
		UserServiceURL:    "http://user:0",
		ClaimsServiceURL:  "http://claims:0",
		AiServiceURL:      "http://ai:0",
		AuditServiceURL:   "http://audit:0",
		FileServiceURL:    "http://file:0",
		PaymentServiceURL: "http://pay:0",
		RulesServiceURL:   "http://rules:0",
	}
	Register(app, cfg)
	return app
}
