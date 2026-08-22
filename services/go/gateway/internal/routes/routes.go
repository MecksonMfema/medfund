package routes

import (
	"github.com/gofiber/fiber/v2"
	"github.com/medfund/gateway/internal/config"
	"github.com/medfund/gateway/internal/middleware"
	"github.com/medfund/gateway/internal/platform"
	"github.com/medfund/gateway/internal/proxy"
)

// Register configures all reverse-proxy route mappings from API prefixes
// to their corresponding backend services.
func Register(app *fiber.App, cfg *config.Config) {
	// ── Platform aggregation (handled by gateway, not proxied) ────────────────
	// Every /api/v1/platform/* endpoint is cross-tenant and must be super-admin
	// only (Phase 9 D9-6). The Angular roleGuard hides the surface client-side;
	// this middleware closes the hole where any authenticated user could hit
	// the platform stats + analytics endpoints directly.
	platformHandler := platform.NewHandler(cfg)
	platformGroup := app.Group("/api/v1/platform", middleware.RequireSuperAdmin())
	platformHandler.Register(platformGroup)

	// ── Tenancy Service ───────────────────────────────────────────────────────
	// Per-tenant high-cost-claimant threshold (Phase 4 V132) registered
	// BEFORE the /api/v1/tenants/* catch-all so Fiber's first-match routing
	// pins the config surface explicitly (both point at tenancy-service;
	// this documents the path and survives a future catch-all split).
	app.All("/api/v1/tenants/*/high-cost-claimant-config", proxy.Handler(cfg.TenancyServiceURL))
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
	// Unified member + dependant typeahead used by the claim-capture UI.
	app.All("/api/v1/beneficiaries", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/beneficiaries/*", proxy.Handler(cfg.UserServiceURL))
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
	// Phase 4 claims-financial report family — per-scheme / per-provider
	// summaries + HIGH_COST_CLAIMANT + PRE_AUTH_ACTIVITY + drill-downs, and
	// the narrow cross-service /aggregate/claims surface consumed by Phase 5
	// loss-ratio. Path-specific per Phase 2 deviation §4 rationale.
	app.All("/api/v1/reports/claims", proxy.Handler(cfg.ClaimsServiceURL))
	app.All("/api/v1/reports/claims/*", proxy.Handler(cfg.ClaimsServiceURL))
	app.All("/api/v1/reports/aggregate/claims", proxy.Handler(cfg.ClaimsServiceURL))
	app.All("/api/v1/reports/aggregate/claims/*", proxy.Handler(cfg.ClaimsServiceURL))

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
	// Per-(member|dependant) benefit utilization vs. scheme limits.
	app.All("/api/v1/beneficiary-benefits", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/beneficiary-benefits/*", proxy.Handler(cfg.ContribServiceURL))
	// Annual cap totals aggregated per (member, scheme, policy year).
	app.All("/api/v1/beneficiary-annual-totals", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/beneficiary-annual-totals/*", proxy.Handler(cfg.ContribServiceURL))
	// Phase 2 billing-report family + cross-service billing aggregate.
	// /api/v1/reports/billing/* — per-scheme / per-group / per-member (Phase 3 §8) / detail / export.
	// /api/v1/reports/aggregate/billing — Phase 3 collection-rate and
	// Phase 5 loss-ratio consumer. Aggregate paths for other families
	// (receipts / claims) fan out to their owning services.
	app.All("/api/v1/reports/billing", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/reports/billing/*", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/reports/aggregate/billing", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/reports/aggregate/billing/*", proxy.Handler(cfg.ContribServiceURL))
	// Phase 3 receipts-report family + cross-service receipts aggregate.
	// Same shape as billing — receipts summary+detail per scheme/group/member
	// plus /aggregate/receipts + /aggregate/receipts/monthly consumed by
	// Phase 3 collection-rate + Phase 8 cash-flow forecast.
	app.All("/api/v1/reports/receipts", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/reports/receipts/*", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/reports/aggregate/receipts", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/reports/aggregate/receipts/*", proxy.Handler(cfg.ContribServiceURL))
	// Phase 8 — 13-week cash-flow forecast. Lives in contributions-service
	// (inflow = local unpaid-invoice ledger; outflow composed from
	// finance-service /aggregate/outflows). Path-specific per D8-10.
	app.All("/api/v1/reports/cash-flow-forecast", proxy.Handler(cfg.ContribServiceURL))
	app.All("/api/v1/reports/cash-flow-forecast/*", proxy.Handler(cfg.ContribServiceURL))

	// ── Finance Service ───────────────────────────────────────────────────────
	app.All("/api/v1/payments/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/payment-runs/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/provider-balances/*", proxy.Handler(cfg.FinanceServiceURL))
	// Unified creditors surface (V072) — providers + members combined. The
	// old provider-balances routes above still forward so the Phase 4 410
	// shim can respond in-band.
	app.All("/api/v1/creditors", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/creditors/*", proxy.Handler(cfg.FinanceServiceURL))
	// Unified notes ledger — V074 replaced Adjustment / DebitNote / CreditNote
	// with a single Note entity carrying `direction ∈ {DEBIT, CREDIT}`.
	app.All("/api/v1/notes", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/notes/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/reconciliations/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/payment-advices/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/tenant-bank-accounts", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/tenant-bank-accounts/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/advance-payments", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/advance-payments/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/ctc-payments", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/ctc-payments/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/member-payables", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/member-payables/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/member-cost-share-liabilities", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/member-cost-share-liabilities/*", proxy.Handler(cfg.FinanceServiceURL))
	// Phase 3 collection-rate report — composes billing + receipts monthly
	// aggregates from contributions-service. Path-specific rather than a
	// catch-all /api/v1/reports/* so Phase 5+ cross-service reports on
	// finance-service can add their own siblings without route conflicts.
	app.All("/api/v1/reports/collection-rate", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/reports/collection-rate/*", proxy.Handler(cfg.FinanceServiceURL))
	// Phase 8 — portfolio-level collection-rate trend + the narrow
	// service-to-service planned-outflow feed consumed by the
	// contributions-service cash-flow forecast. Both path-specific per D8-10.
	app.All("/api/v1/reports/collection-rate-trend", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/reports/collection-rate-trend/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/reports/aggregate/outflows", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/reports/aggregate/outflows/*", proxy.Handler(cfg.FinanceServiceURL))
	// Phase 5 cross-service reports — compose billing + receipts + claims
	// aggregates from contributions-service + claims-service.
	app.All("/api/v1/reports/billing-vs-claims", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/reports/billing-vs-claims/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/reports/member-payments", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/reports/member-payments/*", proxy.Handler(cfg.FinanceServiceURL))
	// Phase 10 Reinsurance — Reinsurer + Treaty CRUD (nested layer /
	// participant / applicable-line / cession-rule editors) live in
	// finance-service. Reports at /api/v1/reports/reinsurance/* land in
	// Phase 4; adding both prefixes upfront so the Phase 4 backend deploys
	// don't require a gateway change.
	app.All("/api/v1/reinsurance/*", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/reports/reinsurance", proxy.Handler(cfg.FinanceServiceURL))
	app.All("/api/v1/reports/reinsurance/*", proxy.Handler(cfg.FinanceServiceURL))

	// ── Rules Service (per-tenant Drools rules) ───────────────────────────────
	app.All("/api/v1/rules", proxy.Handler(cfg.RulesServiceURL))
	app.All("/api/v1/rules/*", proxy.Handler(cfg.RulesServiceURL))
	app.All("/api/v1/rule-templates", proxy.Handler(cfg.RulesServiceURL))
	app.All("/api/v1/rule-templates/*", proxy.Handler(cfg.RulesServiceURL))

	// In-app notifications (bell dropdown) — Java-owned via the shared
	// NotificationsController. Persisted rows live in public.notifications;
	// the Go notification-service handles email fan-out only.
	app.All("/api/v1/notifications", proxy.Handler(cfg.UserServiceURL))
	app.All("/api/v1/notifications/*", proxy.Handler(cfg.UserServiceURL))

	// ── Go Services ───────────────────────────────────────────────────────────
	app.All("/api/v1/audit", proxy.Handler(cfg.AuditServiceURL))
	app.All("/api/v1/audit/*", proxy.Handler(cfg.AuditServiceURL))
	app.All("/api/v1/files/*", proxy.Handler(cfg.FileServiceURL))
	app.All("/api/v1/pay/*", proxy.Handler(cfg.PaymentServiceURL))
}
