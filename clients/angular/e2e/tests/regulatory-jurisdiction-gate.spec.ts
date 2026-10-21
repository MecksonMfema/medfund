import { test, expect } from '../fixtures/test';

/**
 * Phase 27 permission-gate e2e coverage for the AML/STR alerts route
 * (Phase 23 UI). The plan sketched a jurisdiction-gate spec against the
 * regulator report pages, but those UI surfaces don't ship until later
 * — the AML alerts list is the only concrete permission-gated regulator
 * page in the current Angular app, so this spec pins its guard behaviour
 * instead. The 403 on the report submission endpoint (server-side gate)
 * is already covered per-report in the ifrs17-report-toggle sibling
 * spec — the ZW-only / ZA-only / US-only shape is identical there.
 */

test.describe('AML alerts permission gate (Phase 27 REG stand-in for jurisdiction gate)', () => {
  test('operator without compliance:aml_review is redirected to /unauthorized',
      async ({ page, apiMocks, signInAs }) => {
    // Deliberately no compliance:* permissions — only finance:view.
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['finance:view'],
    });

    // The guard runs before any HTTP call; the alerts endpoint stays
    // un-hit. Register a stub only so an unexpected call would fail loud
    // (the base 404 handler surfaces the misfire).
    apiMocks.respond('GET /regulatory/aml/alerts', () => ({
      content: [], total: 0, page: 0, size: 50, totalPages: 0,
    }));

    await page.goto('/tenant/finance/reports/compliance/aml-str/alerts');
    // Router redirects to /unauthorized on guard denial.
    await expect(page).toHaveURL(/\/unauthorized$/);
    await expect(page.getByRole('heading', { name: /403 — Unauthorized/ })).toBeVisible();
  });

  test('operator with compliance:aml_review reaches the alerts list',
      async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['compliance:aml_review'],
    });

    apiMocks.respond('GET /regulatory/aml/alerts', () => ({
      content: [], total: 0, page: 0, size: 50, totalPages: 0,
    }));

    await page.goto('/tenant/finance/reports/compliance/aml-str/alerts');
    await expect(page.getByRole('heading', { name: /^AML\/STR alerts$/i })).toBeVisible();
    await expect(page).not.toHaveURL(/\/unauthorized$/);
  });
});
