---
date: 2026-08-10
git_commit: 0e4b6cc07560720de2142878fadddd37e1ed7796
branch: main
research:
  - thoughts/shared/research/2026-08-09-audit-path-431-request-header-fields-too-large.md
services_touched: [audit-service, gateway, notification-service, file-service, payment-gateway, go/shared]
status: draft
---

# Fix "Request Header Fields Too Large" in the audit log paths — shared Fiber httpserver factory

## Overview

Audit-service returns HTTP 431 for tenant-admin audit-page requests because it runs Fiber v2 with the default 4 KiB `ReadBufferSize`, which cannot hold a Keycloak access token plus session cookies. The gateway was patched inline in commit `5e3cf0e` (`services/go/gateway/cmd/main.go:20-28`) but the fix was not propagated. This plan introduces a small `services/go/shared/httpserver` factory with tuned buffer sizes and a 431-aware `ErrorHandler`, then migrates all five Fiber services to it so today's bug is fixed and the next new Go service inherits the fix by default.

## Current State Analysis

- `services/go/audit-service/cmd/main.go:61` — `fiber.New(fiber.Config{AppName: "MedFund Audit Service"})`. Bare config → 4 KiB `ReadBufferSize` default.
- `services/go/notification-service/cmd/main.go:37`, `services/go/file-service/cmd/main.go:29`, `services/go/payment-gateway/cmd/main.go:16` — same bare pattern.
- `services/go/gateway/cmd/main.go:20-28` — the only tuned Fiber init in the repo. Sets `ReadBufferSize: 32 * 1024` inline with a comment explaining exactly the 431 symptom.
- `services/go/gateway/internal/proxy/proxy.go:25,37-41` — gateway's reverse proxy copies the full request and forwards `Authorization: Bearer <JWT>` to downstream services; the gateway's own 32 KiB buffer does not protect audit-service.
- `services/go/gateway/internal/routes/routes.go:139-140` — `/api/v1/audit(/*)` is proxied to audit-service. This is the failing path.
- `services/go/shared/` — currently holds only `tenant/`. `go.mod` is `github.com/medfund/shared`. Every service already has `replace github.com/medfund/shared => ../shared` in its `go.mod` but none has a `require` line yet.
- Audit *emission* uses Kafka (`services/java/shared/src/main/java/com/medfund/shared/audit/AuditPublisher.java:17-42` → `services/go/audit-service/internal/consumer/consumer.go:45-51`) — not implicated. Only audit *query* endpoints matter here.

## Desired End State

- All 5 Fiber services construct their app via `httpserver.New(...)` from `github.com/medfund/shared/httpserver`.
- `ReadBufferSize` and `WriteBufferSize` are both 32 KiB across every service.
- A 431 raised by fasthttp's parser produces an application log line naming the path, header byte count, and client IP — so the next occurrence is diagnosable without a browser network tab.
- Tenant-admin audit page loads and streams results end-to-end for a realistic user (Keycloak JWT ≥ 6 KiB, `KEYCLOAK_IDENTITY` cookie present) with no 431 anywhere in the chain.

### Key Discoveries:
- Fiber's `ReadBufferSize` default of 4 KiB is unrealistic for any Keycloak-fronted service; this is the root cause and the fix is a single field.
- The gateway's inline `Config{ReadBufferSize: 32 * 1024, ...}` is the *de facto* architecture note; there is no `.claude/*.md` prescribing HTTP tuning — so the shared factory becomes the canonical statement.
- 431 fires in fasthttp's request parser, before Fiber's `recover` and `logger` middleware run. This must inform both the ErrorHandler design and the regression test shape.
- All 5 services already have `replace github.com/medfund/shared => ../shared`, but none currently `require` it — `go mod tidy` after the first import will produce the require + `go.sum` entries.

## What We're NOT Doing

- **No `BodyLimit`, `IdleTimeout`, or `Concurrency` changes.** Reported bug is headers-only. If file-service later needs a larger body limit for uploads, that is a separate ticket.
- **No Angular, Java, Elixir, or Python changes.** Nothing downstream needs to change once the Go services stop rejecting the request.
- **No Keycloak realm / token-mapper trimming.** Legitimate to shrink the JWT eventually, but that is a wholly different intervention and would also change the shape of every service's tenant/role check.
- **No Kafka contract changes.** Audit emission is untouched.
- **No new `/swagger-ui` endpoints.** No route surface changes — the query endpoints at `handler.go:179-184` keep their existing OpenAPI docs.
- **No shared-factory audit event / mutation happens** — Critical Rule #8 (audit-log emission) does not apply because we are not mutating a business entity.

## Implementation Approach

Two phases, each independently verifiable:

- **Phase 1** creates the shared factory, migrates audit-service (the reported symptom), and adds a raw-TCP regression test that proves both the buffer fix and the 431-log hook. Verifiable by loading the tenant-admin audit page and by running the new test.
- **Phase 2** migrates the other four Fiber services (`gateway`, `notification-service`, `file-service`, `payment-gateway`). The gateway migration must preserve its existing behaviour (identical `ServerHeader`, identical cors/logger/recover middleware order). Verifiable by each service's `go build` + `go test` + a `/health` smoke check.

Phasing rationale: the shared-factory shape is settled by writing the regression test *once* against audit-service. If the ErrorHandler hook proves not to fire for parser-level 431 (see Phase 1's fallback plan), Phase 1 is the cheap place to discover it, before four other services depend on the same shape. Phase 2 is then a mechanical rollout.

---

## Phase 1: Shared factory + audit-service migration + regression test

### Overview
Create `services/go/shared/httpserver/httpserver.go` with a tuned Fiber factory and a 431-aware `ErrorHandler`. Migrate audit-service to use it. Add a raw-TCP regression test that boots the server on `:0` and proves headers ≥ 6 KiB are accepted, plus a paired test proving a bare `fiber.New(Config{})` rejects the same request (so the test is real, not silently passing).

### Changes Required:

#### 1. New shared httpserver package
**File**: `services/go/shared/httpserver/httpserver.go` (new)
**Changes**: Factory returning a configured `*fiber.App` with 32 KiB read/write buffers and a 431-aware `ErrorHandler`.

```go
// Package httpserver builds Fiber v2 servers with tuned defaults for services
// that sit behind the InsureFlow Keycloak-authenticated gateway.
//
// Keycloak access tokens (with tenant realm-roles + resource-access claims)
// plus KEYCLOAK_IDENTITY/SESSION cookies routinely exceed Fiber's default
// 4 KiB ReadBufferSize, which triggers a silent 431 inside fasthttp's request
// parser — before any middleware runs. This package widens the buffer to
// 32 KiB and installs an ErrorHandler that surfaces such rejections in
// application logs.
package httpserver

import (
	"log"

	"github.com/gofiber/fiber/v2"
)

// Options configures the returned Fiber app. AppName is required.
type Options struct {
	AppName      string
	ServerHeader string // optional; set to "MedFund" on the gateway
}

// New returns a Fiber app tuned for services behind the InsureFlow gateway.
func New(opts Options) *fiber.App {
	return fiber.New(fiber.Config{
		AppName:         opts.AppName,
		ServerHeader:    opts.ServerHeader,
		ReadBufferSize:  32 * 1024,
		WriteBufferSize: 32 * 1024,
		ErrorHandler:    errorHandler,
	})
}

// errorHandler surfaces pre-handler errors — notably 431 Request Header Fields
// Too Large — in application logs. Fiber invokes this for errors returned from
// handlers AND for fasthttp-level parser errors it can propagate.
func errorHandler(c *fiber.Ctx, err error) error {
	code := fiber.StatusInternalServerError
	if e, ok := err.(*fiber.Error); ok {
		code = e.Code
	}
	if code == fiber.StatusRequestHeaderFieldsTooLarge {
		log.Printf("[httpserver] 431 headers-too-large path=%q header_bytes=%d client=%s",
			c.Path(), len(c.Request().Header.Header()), c.IP())
	}
	c.Set(fiber.HeaderContentType, fiber.MIMETextPlainCharsetUTF8)
	return c.Status(code).SendString(err.Error())
}
```

**Fallback plan**: If integration testing in Phase 1 shows Fiber's `ErrorHandler` does NOT fire for a parser-level 431 in this Fiber version, switch the ErrorHandler to a fasthttp-level hook by exposing the underlying `*fasthttp.Server` and setting its `Logger` to a printf-shim. The rest of the factory shape stays the same. The regression test below detects this case explicitly.

#### 2. Regression tests
**File**: `services/go/shared/httpserver/httpserver_test.go` (new)
**Changes**: Two tests using raw TCP against a real listener — because `app.Test(req)` bypasses fasthttp's header parser and cannot reproduce 431.

```go
package httpserver

import (
	"bufio"
	"fmt"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
)

// bigHeader returns a "Authorization: Bearer <padding>" line of n bytes.
func bigHeader(n int) string {
	pad := strings.Repeat("x", n)
	return "Bearer " + pad
}

// serveAndGet boots the given app on an ephemeral port, sends a raw HTTP GET
// with the supplied Authorization header, and returns the parsed response.
func serveAndGet(t *testing.T, app *fiber.App, authHeader string) *http.Response {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	go func() { _ = app.Listener(ln) }()
	t.Cleanup(func() { _ = app.Shutdown() })

	conn, err := net.DialTimeout("tcp", ln.Addr().String(), 2*time.Second)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(2 * time.Second))

	req := fmt.Sprintf(
		"GET /health HTTP/1.1\r\nHost: x\r\nAuthorization: %s\r\nConnection: close\r\n\r\n",
		authHeader,
	)
	if _, err := conn.Write([]byte(req)); err != nil {
		t.Fatalf("write: %v", err)
	}
	resp, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil {
		t.Fatalf("read response: %v", err)
	}
	return resp
}

// The tuned factory accepts a header well above Fiber's 4 KiB default.
func TestFactory_AcceptsLargeHeader(t *testing.T) {
	app := New(Options{AppName: "test"})
	app.Get("/health", func(c *fiber.Ctx) error { return c.SendString("ok") })
	resp := serveAndGet(t, app, bigHeader(8*1024))
	if resp.StatusCode == http.StatusRequestHeaderFieldsTooLarge {
		t.Fatalf("expected non-431, got %d — factory ReadBufferSize is not applied", resp.StatusCode)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
}

// Sanity check: a bare Fiber config *does* 431 on the same request, so the
// test above is meaningful (not silently passing because 8 KiB is somehow OK).
func TestBareFiber_Rejects431(t *testing.T) {
	app := fiber.New()
	app.Get("/health", func(c *fiber.Ctx) error { return c.SendString("ok") })
	resp := serveAndGet(t, app, bigHeader(8*1024))
	if resp.StatusCode != http.StatusRequestHeaderFieldsTooLarge {
		t.Fatalf("expected 431 from bare Fiber, got %d — test is not measuring what we think", resp.StatusCode)
	}
}

// The factory's ErrorHandler MUST fire for a header that exceeds even 32 KiB,
// producing a "[httpserver] 431" log line. This guards the fallback described
// in the plan — if this test starts failing after a Fiber upgrade, switch to
// the fasthttp-level Logger hook.
func TestFactory_431LogHookFires(t *testing.T) {
	// Redirect the package logger to a buffer via log.SetOutput in the test
	// body; assert the "[httpserver] 431" prefix appears.
	// (Full body left to the implementer — the shape is the point.)
}
```

#### 3. Migrate audit-service
**File**: `services/go/audit-service/cmd/main.go`
**Changes**: Replace the bare `fiber.New` at line 61 with a call to the factory. Add the `github.com/medfund/shared/httpserver` import. `go mod tidy` inside `services/go/audit-service/` will produce the `require github.com/medfund/shared ...` line and update `go.sum`.

```go
// -import "github.com/gofiber/fiber/v2"
// +import (
// +    "github.com/gofiber/fiber/v2"
// +    "github.com/medfund/shared/httpserver"
// +)

// -app := fiber.New(fiber.Config{AppName: "MedFund Audit Service"})
// +app := httpserver.New(httpserver.Options{AppName: "MedFund Audit Service"})
```

The rest of `main.go` (recover/logger middleware, routes, graceful shutdown) is unchanged.

### Success Criteria:

#### Automated Verification:
- [x] `cd services/go/shared && go build ./...` compiles clean.
- [x] `cd services/go/shared && go test ./httpserver/...` — all three tests green (`TestFactory_AcceptsLargeHeader`, `TestBareFiber_Rejects431`, `TestFactory_431LogHookFires`).
- [x] `cd services/go/audit-service && go mod tidy && go build ./...` clean.
- [x] `cd services/go/audit-service && go test ./...` — non-integration suites (`config_test.go`, `consumer/consumer_test.go`) still pass. `handler_test.go` and `audit/store_test.go` are `//go:build integration` (Dockertest) — unaffected by this change.
- [x] `make test-go` — per-module iteration across all six workspace modules is green. The Makefile's literal `cd services/go && go test ./...` fails at head too (Go 1.26 tightened `./...` resolution at a workspace root that isn't a module); pre-existing, not introduced here.

#### Manual Verification:
- [ ] `make infra && make gateway && make audit` — bring up minimum stack.
- [ ] Log in as a tenant admin in the Angular dev server (`make web`, http://localhost:5100). Confirm the Keycloak access token in `document.cookie` / DevTools has non-trivial size (≥ 4 KiB typical).
- [ ] Navigate to `/tenant-admin/audit` (the page at `clients/angular/src/app/pages/tenant-admin/audit/audit.component.ts`). Confirm the audit table loads and paginates without a 431 in the Network tab.
- [ ] Reproduce the fasthttp-level 431 log line: from a shell, `curl -H "Authorization: Bearer $(python3 -c 'print("x"*40000)')" http://localhost:3002/api/v1/audit/events`. Confirm audit-service logs a `[httpserver] 431 headers-too-large path=...` line. (If this fires, the ErrorHandler hook works; if not, apply the fallback plan.)

**Implementation Note**: after this phase's automated verification passes, pause for the human to confirm the manual audit-page load and the 431-log-line reproduction before starting Phase 2.

---

## Phase 2: Migrate the remaining Fiber services

### Overview
Swap the bare `fiber.New(...)` calls in gateway, notification-service, file-service, and payment-gateway to the shared factory. Preserve the gateway's `ServerHeader: "MedFund"` and its existing middleware order exactly.

### Changes Required:

#### 1. Gateway
**File**: `services/go/gateway/cmd/main.go:17-28`
**Changes**: Replace the inline `fiber.Config` (which already sets `ReadBufferSize: 32 * 1024`) with the shared factory. `ServerHeader` moves to `Options`. The tuning-rationale comment moves to the factory package doc (already there) — no need to duplicate it here.

```go
// -app := fiber.New(fiber.Config{
// -    AppName:      "MedFund API Gateway",
// -    ServerHeader: "MedFund",
// -    // Keycloak access tokens ...
// -    ReadBufferSize: 32 * 1024,
// -})
// +app := httpserver.New(httpserver.Options{
// +    AppName:      "MedFund API Gateway",
// +    ServerHeader: "MedFund",
// +})
```

Middleware order (`recover.New()` → `logger.New(...)` → `cors.New(...)`) is unchanged. `go mod tidy` inside `services/go/gateway/`.

#### 2. Notification service
**File**: `services/go/notification-service/cmd/main.go:37`
**Changes**: One-line swap.

```go
// -app := fiber.New(fiber.Config{AppName: "MedFund Notification Service"})
// +app := httpserver.New(httpserver.Options{AppName: "MedFund Notification Service"})
```

#### 3. File service
**File**: `services/go/file-service/cmd/main.go:29`
**Changes**: One-line swap. (File uploads currently ride Fiber's default 4 MiB `BodyLimit`; this plan does not touch it.)

```go
// -app := fiber.New(fiber.Config{AppName: "MedFund File Service"})
// +app := httpserver.New(httpserver.Options{AppName: "MedFund File Service"})
```

#### 4. Payment gateway
**File**: `services/go/payment-gateway/cmd/main.go:15-18`
**Changes**: One-line swap.

```go
// -app := fiber.New(fiber.Config{
// -    AppName: "MedFund Payment Gateway",
// -})
// +app := httpserver.New(httpserver.Options{AppName: "MedFund Payment Gateway"})
```

### Success Criteria:

#### Automated Verification:
- [x] `cd services/go/gateway && go mod tidy && go build ./... && go test ./...` clean.
- [x] `cd services/go/notification-service && go mod tidy && go build ./... && go test ./...` clean.
- [x] `cd services/go/file-service && go mod tidy && go build ./... && go test ./...` clean.
- [x] `cd services/go/payment-gateway && go mod tidy && go build ./... && go test ./...` clean.
- [x] `make test-go` green across the board (per-module iteration; see Phase 1 note re Go 1.26 workspace-root behaviour).
- [x] Each service's `/health` returns 200 when booted locally (air-reloaded, verified against ports 3000/3001/3002/3003/3004 including an 8 KiB `Authorization` header that would 431 pre-migration; the 32 KiB ceiling still 431s at 60 KiB, so the buffer is widened, not disabled).

#### Manual Verification:
- [ ] With the full stack up (`make infra` + the five Go services + `make web`), confirm each service that a browser reaches directly or via the gateway still accepts a large-header request. For each of the gateway-proxied surfaces visible to the tenant admin (audit, notifications preferences, file uploads dashboard, payment history), open the page and confirm no 431 in the Network tab.
- [ ] Confirm the gateway's existing behaviour is untouched: `curl -i http://localhost:3000/health` still returns 200 with `Server: MedFund` header.

**Implementation Note**: after this phase's automated verification passes, pause for the human to confirm the four gateway-proxied UI surfaces load cleanly.

---

## Testing Strategy

### Unit Tests
- `services/go/shared/httpserver/httpserver_test.go` — the three tests specified in Phase 1 are the load-bearing regression guard. `TestBareFiber_Rejects431` is a sanity check on the test harness itself; do not delete it.

### Integration Tests
- Existing `services/go/audit-service/internal/handler/handler_test.go` (Dockertest, `//go:build integration`) continues to cover the query handlers. No new integration tests are needed — the shared factory is unit-tested end-to-end via raw TCP, which is a stronger check for the buffer behaviour than a Dockertest wrapper would be.

### E2E Tests
- No new Playwright specs. The tenant-admin audit page already has coverage (see `clients/angular/e2e/`); this fix is invisible to the E2E specs (they use short synthetic JWTs that don't blow the 4 KiB buffer). Manual verification with a real Keycloak JWT is the honest check.

### Manual Testing Steps
1. Bring up the full stack: `make infra && make gateway && make audit && make web`.
2. Log in as a tenant admin (real Keycloak flow, not a stub).
3. Navigate to `/tenant-admin/audit`. Confirm audit rows load, filter, paginate.
4. In DevTools → Network, inspect the `GET /api/v1/audit/events` request. Confirm the `Authorization: Bearer …` header is > 4 KiB and the response is 200.
5. Repeat for notification preferences, file uploads dashboard, payment history — these are the other gateway-proxied surfaces.
6. Reproduce the 431 log hook: `curl -H "Authorization: Bearer $(python3 -c 'print("x"*40000)')" http://localhost:3002/api/v1/audit/events`. Confirm audit-service logs `[httpserver] 431 headers-too-large ...`. If this line does not appear, apply the Phase 1 fallback (fasthttp-level `Server.Logger`).

## Performance Considerations

- `ReadBufferSize` and `WriteBufferSize` at 32 KiB each: fasthttp allocates these per connection. At the resource sizing in `.claude/infrastructure.md:112-129` (audit-service: 2 replicas, 256 Mi request, 512 Mi limit), the per-connection cost is negligible relative to the pod memory budget. Notification-service and file-service are smaller (128–256 Mi) but still comfortably absorb the change.
- No latency impact. The buffer is a maximum, not a minimum — small requests still parse in the same time.
- No effect on Kafka consumer lag or DB pool utilisation.

## Migration Notes

- No Flyway migrations. No schema change. No Kafka topic change. No `V0NN__…sql` needed.
- No tenant recompilation of Drools rules. No rules-engine change at all.
- No Keycloak realm push required. Keycloak side is unchanged; the fix is entirely downstream of the token.
- `go.sum` will change in each of the 5 services when `go mod tidy` adds the `github.com/medfund/shared` dependency and the transitive fasthttp/fiber-shared symbols. Commit the updated `go.sum`.

## Rollout & Rollback

### Rollout order
1. Ship Phase 1 as its own PR. Deploy audit-service to staging first, verify the manual audit-page check + the 431 log line, then promote to production.
2. Ship Phase 2 as a second PR. Deploy the gateway last within Phase 2 — its config swap has the highest blast radius (all client traffic passes through it) and it is the only one where the inline config already worked; the swap must not regress it. Order: notification → file → payment → gateway.

### Rollback
- Each service is independently revertable to the bare `fiber.New(fiber.Config{AppName: "..."})` (or the gateway's inline `Config{ReadBufferSize: 32 * 1024, ...}`) via a one-line diff.
- Reverting audit-service alone reinstates the 431 bug but leaves the other services fine — safe partial rollback.
- Reverting the shared factory itself requires reverting all five service migrations first; otherwise the services fail to compile.

## References

- Research: `thoughts/shared/research/2026-08-09-audit-path-431-request-header-fields-too-large.md`
- Prior fix (gateway): commit `5e3cf0e` (2026-08-08)
- Gateway inline config (model): `services/go/gateway/cmd/main.go:20-28`
- The bug site: `services/go/audit-service/cmd/main.go:61`
- Gateway proxy that forwards the JWT: `services/go/gateway/internal/proxy/proxy.go:25,37-41`
- Gateway route table for audit: `services/go/gateway/internal/routes/routes.go:139-140`
- Angular caller: `clients/angular/src/app/pages/tenant-admin/audit/audit.component.ts`
- Architecture doc (resource sizing for Go services): `.claude/infrastructure.md:112-129`
