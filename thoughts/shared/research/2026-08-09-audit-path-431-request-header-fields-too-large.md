---
date: 2026-08-09T00:00:00Z
researcher: Methuseli
git_commit: 0e4b6cc07560720de2142878fadddd37e1ed7796
branch: main
repository: medfund
topic: "Request Header Fields Too Large in the audit log paths"
tags: [research, codebase, audit-service, gateway, fiber, keycloak, http-431]
status: complete
last_updated: 2026-08-09
last_updated_by: Methuseli
---

# Research: Request Header Fields Too Large in the audit log paths

**Date**: 2026-08-09 · **Researcher**: Methuseli · **Commit**: 0e4b6cc · **Branch**: main

## Research Question
Why is the audit log path returning HTTP 431 "Request Header Fields Too Large"? Where in the request chain is the header size limit being hit, and what needs to change?

## Summary
The 431 originates in **audit-service**, which is still running on Fiber v2's **default 4 KiB `ReadBufferSize`**. The gateway was already patched to 32 KiB in commit `5e3cf0e` for exactly this reason (Keycloak access tokens + `KEYCLOAK_IDENTITY`/`SESSION` cookies routinely blow past 4 KiB), but the same fix was not propagated to any of the other Fiber-based Go services. When the tenant-admin audit page fires `GET /api/v1/audit/events`, the gateway happily accepts the fat request, injects `Authorization: Bearer <jwt>` on the proxied call, and hands it to audit-service — which rejects it with 431 before any middleware runs. The gateway then forwards the 431 back to the browser.

**Audit *emission* is not implicated.** All Java services publish audit events to Kafka (`medfund.audit.events`); audit-service exposes no ingestion HTTP endpoint. Only *audit query* traffic (the tenant-admin audit page, dashboards) traverses HTTP.

**One-line fix**: mirror the gateway's `ReadBufferSize: 32 * 1024` in `services/go/audit-service/cmd/main.go:61`. The same latent bug is present in every other Go/Fiber service (`notification-service`, `file-service`, `payment-gateway`) and should be fixed alongside it, or extracted into `services/go/shared`.

## Findings

### Gateway — already fixed
- `services/go/gateway/cmd/main.go:20-28` — Fiber initialised with `ReadBufferSize: 32 * 1024`. The comment at lines 23-26 is the exact explanation of the 431 symptom: "Keycloak access tokens (with tenant realm-roles + resource-access claims) plus KEYCLOAK_IDENTITY/SESSION cookies routinely push request headers past Fiber's 4 KiB default, which triggers a 431 *before* the CORS middleware runs — the browser then surfaces it as a misleading CORS error."
- Commit `5e3cf0e` (2026-08-08) introduced this fix.

### Audit-service — NOT fixed
- `services/go/audit-service/cmd/main.go:61` — `app := fiber.New(fiber.Config{AppName: "MedFund Audit Service"})`. No `ReadBufferSize` override → **4 KiB default**.
- `services/go/audit-service/cmd/main.go:62-63` — Only `recover.New()` and `logger.New()` middleware. The 431 is raised inside fasthttp's request parser before either runs, so no log line surfaces the reason.
- `services/go/audit-service/internal/handler/handler.go:179-184` — HTTP routes exposed:
  - `GET /api/v1/audit/events` (query)
  - `GET /api/v1/audit/events/daily-counts` (chart)
  - `GET /api/v1/audit/stats` (KPI)
  - Plus `GET /health` (main.go:65-67, unauthenticated)
- `services/go/audit-service/internal/handler/handler.go:58-76` — `scopeFromRequest()` requires either `X-Tenant-ID: <uuid>` or `X-Platform-Scope: all`, in addition to the Authorization header — the header footprint is real.

### Gateway → audit-service proxy path
- `services/go/gateway/internal/routes/routes.go:139-140` — `app.All("/api/v1/audit", ...)` and `app.All("/api/v1/audit/*", ...)` both proxy to `cfg.AuditServiceURL`.
- `services/go/gateway/internal/proxy/proxy.go:25` — the entire upstream request is copied from the incoming Fiber request (`c.Request().CopyTo(req)`), so all headers accompany it downstream.
- `services/go/gateway/internal/proxy/proxy.go:37-41` — Authorization is either set from the validated JWT (`c.Locals("jwt_token")`) or forwarded verbatim. That's the full Keycloak access token, not a trimmed marker.
- The gateway's 32 KiB buffer only protects the **client → gateway** hop; the **gateway → audit-service** hop is bounded by audit-service's own buffer.

### Audit event **emission** is Kafka-only, not HTTP
- `services/java/shared/src/main/java/com/medfund/shared/audit/AuditPublisher.java:17-42` — every Java service emits audit events to the Kafka topic `medfund.audit.events` via `KafkaSender<String, String>`. There is no HTTP fallback.
- `services/java/keycloak-event-listener/src/main/java/com/medfund/keycloak/SecurityEventPublisher.java:37-52` — security events likewise publish to `medfund.security.events`.
- `services/go/audit-service/internal/consumer/consumer.go:45-51` — audit-service consumes those three topics and persists to Postgres. It exposes **no POST/PUT** ingestion endpoint.
- Corollary: the 431 is on the **read side** (audit page / dashboard queries), not on writes. The read side is the only surface where a browser-sized JWT + cookies reaches audit-service.

### Angular caller
- `clients/angular/src/app/pages/tenant-admin/audit/audit.component.ts` — the tenant-admin audit page is the primary caller of `/api/v1/audit/events`. This is the request that surfaces the 431 in the UI.

## Cross-service flow
```
Browser (Angular, tenant admin, big Keycloak JWT + KEYCLOAK_IDENTITY cookie)
   │
   │  GET /api/v1/audit/events?…      Cookie: KEYCLOAK_IDENTITY=…
   │  Authorization: Bearer <8-16 KiB access token>
   ▼
Angular nginx sidecar  (default nginx header buffers — pass-through)
   │
   ▼
gateway  (Fiber, ReadBufferSize=32 KiB — OK)
   │  proxy.go: copies incoming request, sets Authorization: Bearer <jwt>
   │  X-Tenant-ID forwarded
   ▼
audit-service  (Fiber, ReadBufferSize=**4 KiB default** — REJECTS)
   │  fasthttp raises 431 in the request parser — before recover/logger middleware
   ▼
gateway  proxies the 431 back
   ▼
Browser surfaces 431 (or, if it happens before CORS response headers, a CORS-shaped error)
```

## Architecture doc vs. code
- `.claude/portals.md` and `.claude/infrastructure.md` do not prescribe HTTP tuning knobs for the Go services — this is a code-only decision. The only place the buffer size is discussed in the repo is the inline comment at `services/go/gateway/cmd/main.go:23-26`. That comment is the *de facto* architecture note, and it hasn't been generalised into shared bootstrap code. The other Go services carry the latent bug because there is no shared server factory that would have picked up the gateway's fix.

## Code References
- `services/go/gateway/cmd/main.go:20-28` — 32 KiB buffer applied here.
- `services/go/gateway/internal/routes/routes.go:139-140` — `/api/v1/audit(/*)` routed to audit-service.
- `services/go/gateway/internal/proxy/proxy.go:25,37-41` — full request copy + Authorization forwarding.
- `services/go/audit-service/cmd/main.go:61` — **the fix site**; default Fiber config, no buffer override.
- `services/go/audit-service/internal/handler/handler.go:58-76,179-184` — endpoints and the tenant/platform-scope header enforcement.
- `services/java/shared/src/main/java/com/medfund/shared/audit/AuditPublisher.java:17-42` — Kafka-only audit emission (rules out audit-emission as the culprit).
- `services/go/audit-service/internal/consumer/consumer.go:45-51` — Kafka consumption confirms no HTTP ingestion path.
- `clients/angular/src/app/pages/tenant-admin/audit/audit.component.ts` — the front-end caller most likely to trigger this in the wild.

## Architecture Insights
- **Fiber's 4 KiB default is unrealistic for a Keycloak-fronted platform.** Any request that ships a full access token + Keycloak session cookie will breach it. Every Fiber-based Go service in this repo should be running with a widened `ReadBufferSize`.
- **The gateway fix is not enough.** The gateway proxies the same headers downstream, so every Fiber service behind it inherits the same failure mode unless individually widened. Suggests a `services/go/shared/httpserver` (or similar) factory that returns `fiber.New(fiber.Config{ReadBufferSize: 32 * 1024, ...})` and is called by every service's `main.go`.
- **Silent failure mode.** fasthttp raises 431 *inside* the request parser, before `recover`/`logger` middleware runs — so there is no application log line to correlate with the browser error. Debugging requires either curl-with-`-v` reproduction or an infra-level access log. Worth noting for future incidents.
- **Same bug exists in the other Go services.** Spot check:
  - `services/go/notification-service/cmd/main.go` — same default Fiber init pattern.
  - `services/go/file-service/cmd/main.go` — same.
  - `services/go/payment-gateway/cmd/main.go` — same.
  These are latent 431s waiting to happen the moment they get called from a browser with a bearer token.
- **Critical Rule #8 (audit-log emission) is not affected.** All mutation audit events go via Kafka; the 431 does not lose any writes. It only breaks the audit *query* surface used by tenant-admin.

## Historical Context (from thoughts/shared/)
- No prior research or plan documents mention 431 / `ReadBufferSize` / audit-service HTTP failures. The only artefact is the inline comment + commit `5e3cf0e` that patched the gateway.

## Related Research
- None directly. Adjacent context: `.claude/portals.md` (tenant-admin portal spec, which owns the audit UI) and audit-emission memory `feedback_audit_actor_email.md` / `feedback_audit_entity_name.md` — those govern *what* audit records carry, not *how* they're queried.

## Open Questions
- Should the fix live in every service's `main.go`, or should we introduce `services/go/shared/httpserver.New(...)` and route every service through it? The latter is one file to update the next time we bump the buffer or add a global middleware.
- Are there other Fiber defaults (`WriteBufferSize` for large response payloads, `Concurrency`, `IdleTimeout`, `BodyLimit`) that also warrant a shared baseline? The audit query responses can be sizable (paginated events).
- Do we want to add a Fiber `ErrorHandler` that logs 431s explicitly, so the next occurrence is diagnosable from application logs rather than by grepping the browser network tab?
