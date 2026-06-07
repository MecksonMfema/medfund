# MedFund E2E Suite

Playwright tests for the operational portal. Designed to run **without the full polyglot backend stack** by stubbing Keycloak and `/api/v1/*` responses through `page.route()`.

## Current state

| Layer | Status |
|---|---|
| Playwright workspace (config, deps, tsconfig) | ✓ ready |
| `apiMocks` router for `/api/v1/*` | ✓ ready |
| 11 spec definitions across 3 flows | ✓ TypeScript-clean and discovered by `playwright test --list` |
| `Keycloak` stub (`onLoad: 'login-required'` redirect dance) | ⚠ in progress — see *Auth bypass* below |
| Runtime green | ⏳ pending Keycloak-bypass landing |

The Playwright suite **does not yet run end-to-end on this branch**. The infrastructure is fully assembled and the specs encode the regression checks we want; what's missing is a clean way to short-circuit the `keycloak-js` OIDC initialization at app boot. Two unblockers are possible (pick one in the next session):

1. **Test-mode env flag in `keycloak.init.ts`** — when `environment.testMode === true`, skip the `keycloak.init({ onLoad: 'login-required' })` call and seed an in-memory token directly. ~10-line patch, lets the stubbed-API suite run.
2. **Live-stack mode** — boot the real Keycloak via `make infra`, seed a known dev user via `scripts/bootstrap-keycloak.sh`, drop the keycloak stub from `fixtures/auth.ts`, and have the test fixture log in by submitting the real login form. Slower but exercises the real OIDC path.

## Why stubbed (and not live-stack) by default

The Phase 4 mandate is *regression coverage for the Angular UI* — guards, layouts, permission gating, route navigation. Spinning up Postgres + Keycloak + 6 Java services + 5 Go services for every CI run is fragile and slow. The stubs encode the production API contract so a contract drift surfaces as a 4xx in the test rather than a misleading green.

A future "live stack" mode (Phase 4.5) will use the same specs but disable the stubs — the test logic doesn't change.

## Run

From this directory:

```bash
# First-time setup (once per machine)
npm install
npx playwright install --with-deps chromium

# Run the suite
npm test

# Iterate on one spec, with browser visible
npx playwright test scheme-creation --headed

# Step through a single test
npm run test:debug

# Open the HTML report from the last run
npm run report
```

The Playwright `webServer` hook auto-starts `ng serve` from `../`, so you do not need to start the Angular dev server separately.

## Adding a flow

1. Create `tests/<name>.spec.ts`
2. Import `test` from `../fixtures/test` (not `@playwright/test` directly) — this layers auth + API mocks
3. Use `apiMocks.respond('GET /me/permissions', [...])` to override the default permission set
4. See the existing three flows for patterns

## Caveats

- **Mocked Keycloak**: `page.route()` intercepts the realm config + token endpoints so `keycloak-js` thinks it's authenticated. We never exercise the real OIDC code path here; live-stack tests do.
- **Mocked APIs**: every `/api/v1/*` call goes through `apiMocks`. An un-stubbed call returns 404, so an unexpected API hit surfaces as a test failure (not a silent pass).
- **Host OS support**: Playwright's browser binaries do not yet ship for Ubuntu 26.04 (as of mid-2026). If you hit `Playwright does not support chromium on ubuntu26.04-x64`, run the suite in CI (Ubuntu 24.04 runner) or use a system Chromium via `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=$(which chromium)`.
