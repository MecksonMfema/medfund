package routes

import (
	"github.com/gofiber/fiber/v2"
	"github.com/medfund/gateway/internal/config"
	"github.com/medfund/gateway/internal/platform"
	"github.com/medfund/gateway/internal/proxy"
)

// Register configures all reverse-proxy route mappings from API prefixes
// to their corresponding backend services.
func Register(app *fiber.App, cfg *config.Config) {
	// ── Platform aggregation (handled by gateway, not proxied) ────────────────
	platformHandler := platform.NewHandler(cfg)
	platformHandler.Register(app.Group("/api/v1/platform"))

	// ── Tenancy Service ───────────────────────────────────────────────────────
	app.All("/api/v1/tenants/*", proxy.Handler(cfg.TenancyServiceURL))
	app.All("/api/v1/plans/*", proxy.Handler(cfg.TenancyServiceURL))
	app.All("/api/v1/currencies", proxy.Handler(cfg.TenancyServiceURL))
	app.All("/api/v1/currencies/*", proxy.Handler(cfg.TenancyServiceURL))
	app.All("/api/v1/exchange-rates", proxy.Handler(cfg.TenancyServiceURL))
	app.All("/api/v1/exchange-rates/*", proxy.Handler(cfg.TenancyServiceURL))

	// ── User Service ──────────────────────────────────────────────────────────
	app.All("/api/v1/staff-users", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/staff-users/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/members", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/members/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/dependants", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/dependants/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/providers", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/providers/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/groups", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/groups/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/group-liaisons", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/group-liaisons/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/roles", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/roles/*", proxy.Handler(cfg.UserServiceURL))
	// RBAC: permission catalogue + caller's effective permissions.
	app.All("/api/v1/permissions/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/me/permissions", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/scheduled-jobs/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/email-senders", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/email-senders/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/email-campaigns", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/email-campaigns/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/tenant-stats", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/tenant-stats/*", proxy.Handler(cfg.UserServiceURL))

	// ── Per-line policy/asset entities (V032 multi-line build) ────────────────
	// MOTOR / vehicles + PROPERTY / properties are asset-centric;
	// LIFE / FUNERAL / TRAVEL / DISABILITY are person-insuring policies
	// keyed off a NOT NULL FK to members.id. All six get the same
	// CRUD + suspend/terminate + clear-billing-override surface.
	app.All("/api/v1/vehicles", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/vehicles/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/properties", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/properties/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/life-policies", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/life-policies/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/funeral-policies", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/funeral-policies/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/travel-policies", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/travel-policies/*", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/disability-policies", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/disability-policies/*", proxy.Handler(cfg.UserServiceURL))

	// ── Claims Service ────────────────────────────────────────────────────────
	app.All("/api/v1/claims/*", proxy.Handler(cfg.ClaimsServiceURL))
	app.All("/api/v1/tariffs/*", proxy.Handler(cfg.ClaimsServiceURL))
	app.All("/api/v1/pre-authorizations/*", proxy.Handler(cfg.ClaimsServiceURL))
	app.All("/api/v1/icd-codes/*", proxy.Handler(cfg.ClaimsServiceURL))
	app.All("/api/v1/rejection-reasons", proxy.Handler(cfg.ClaimsServiceURL))
	app.All("/api/v1/rejection-reasons/*", proxy.Handler(cfg.ClaimsServiceURL))
	app.All("/api/v1/drugs", proxy.Handler(cfg.ClaimsServiceURL))
	app.All("/api/v1/drugs/*", proxy.Handler(cfg.ClaimsServiceURL))
	app.All("/api/v1/drug-claims", proxy.Handler(cfg.ClaimsServiceURL))
	app.All("/api/v1/drug-claims/*", proxy.Handler(cfg.ClaimsServiceURL))

	// ── Contributions Service ─────────────────────────────────────────────────
	app.All("/api/v1/schemes", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/schemes/*", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/contributions", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/contributions/*", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/invoices/*", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/transactions", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/transactions/*", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/billing/*", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/waiting-periods", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/waiting-periods/*", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/scheme-change-waiting-periods", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/scheme-change-waiting-periods/*", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/statements", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/statements/*", proxy.Handler(cfg.ContribServiceURL))

	// ── Finance Service ───────────────────────────────────────────────────────
	app.All("/api/v1/payments/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/payment-runs/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/provider-balances/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/adjustments/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/reconciliations/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/payment-advices/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/masca-bank-accounts", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/masca-bank-accounts/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/debit-notes", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/debit-notes/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/credit-notes", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/credit-notes/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/advance-payments", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/advance-payments/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/ctc-payments", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/ctc-payments/*", proxy.Handler(cfg.FinanceServiceURL))

	// ── Rules Service (per-tenant Drools rules) ───────────────────────────────
	app.All("/api/v1/rules", proxy.Handler(cfg.RulesServiceURL))
	app.All("/api/v1/rules/*", proxy.Handler(cfg.RulesServiceURL))
	app.All("/api/v1/rule-templates", proxy.Handler(cfg.RulesServiceURL))
	app.All("/api/v1/rule-templates/*", proxy.Handler(cfg.RulesServiceURL))

	// ── Go Services ───────────────────────────────────────────────────────────
	app.All("/api/v1/notifications/*", proxy.Handler(cfg.NotifServiceURL))
	app.All("/api/v1/audit", proxy.Handler(cfg.AuditServiceURL))
	app.All("/api/v1/audit/*", proxy.Handler(cfg.AuditServiceURL))
	app.All("/api/v1/files/*", proxy.Handler(cfg.FileServiceURL))
	app.All("/api/v1/pay/*", proxy.Handler(cfg.PaymentServiceURL))
}
