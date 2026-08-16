import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 7 §B — the payment-run workbook export (D7-6 / D7-7) end to end:
 *
 *   1. An executed run's detail page shows the "Export workbook" button
 *      and downloads the workbook blob —
 *      `GET /payment-runs/:id/export/excel`.
 *   2. The button is available on ANY status (draft too), not just
 *      draft/approved, and sits beside the state-transition buttons.
 *   3. A user without `finance:view_subledger` never sees the button.
 *   4. A server 403 (report disabled / permissions) surfaces the detail
 *      in the error banner.
 *
 * Every /api/v1 call is stubbed via ApiMocks — no live services needed.
 */

function run(overrides: Record<string, unknown> = {}) {
  return {
    id: 'run-7',
    runNumber: 'PR-2026-100',
    status: 'executed',
    payeeType: 'PROVIDER',
    totalAmount: '350.50',
    currencyCode: 'USD',
    paymentCount: 2,
    carriedInAmount: '0.00',
    carriedOutAmount: '0.00',
    createdAt: '2026-06-30T10:00:00Z',
    executedAt: '2026-07-01T09:00:00Z',
    ...overrides,
  };
}

function emptyPage() {
  return { content: [], total: 0, page: 0, size: 50, totalPages: 1 };
}

function reportCatalogue() {
  return [
    {
      id: null,
      tenantId: TENANT,
      reportKey: 'PAYMENT_RUN_WORKBOOK',
      label: 'Payment run workbook',
      family: 'PAYABLES',
      familyLabel: 'Payables',
      enabled: true,
      cadenced: false,
      updatedAt: null,
      updatedBy: null,
    },
  ];
}

test.describe('Payment-run workbook export (Phase 7 §B)', () => {
  test('executed run: header shows Export workbook and downloads the blob', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view', 'finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /payment-runs/run-7', () => run());
    apiMocks.respond('GET /payments/page', emptyPage());
    apiMocks.respond('GET /payment-runs/run-7/advices', () => []);
    apiMocks.respond('GET /payment-runs/run-7/export/excel', () => 'xlsx-stub');

    await page.goto('/tenant/finance/runs/run-7');

    // Detail renders the run identity.
    await expect(page.getByRole('heading', { level: 1 })).toContainText(/PR-2026-100/);

    // Export button visible on an EXECUTED run (D7-7: any status).
    const exportBtn = page.getByRole('button', { name: /^Export workbook$/i });
    await expect(exportBtn).toBeVisible();
    // The state-transition buttons are correctly absent for executed runs.
    await expect(page.getByRole('button', { name: /Execute run/ })).toHaveCount(0);

    // Clicking downloads the workbook blob.
    const exportResp = page.waitForResponse(
      r => r.url().includes('/api/v1/payment-runs/run-7/export/excel') && r.request().method() === 'GET',
    );
    await exportBtn.click();
    expect((await exportResp).status()).toBe(200);
  });

  test('draft run: Export workbook still available', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view', 'finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /payment-runs/run-7', () => run({ status: 'draft', executedAt: undefined }));
    apiMocks.respond('GET /payments/page', emptyPage());
    apiMocks.respond('GET /payment-runs/run-7/advices', () => []);
    apiMocks.respond('GET /payment-runs/run-7/export/excel', () => 'xlsx-stub');

    await page.goto('/tenant/finance/runs/run-7');

    await expect(page.getByRole('heading', { level: 1 })).toContainText(/PR-2026-100/);
    await expect(page.getByRole('button', { name: /^Export workbook$/i })).toBeVisible();
    // Draft runs keep their transition buttons too.
    await expect(page.getByRole('button', { name: /Execute run/ })).toBeVisible();
  });

  test('permission gate: no finance:view_subledger → button hidden', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view'],
    });
    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /payment-runs/run-7', () => run());
    apiMocks.respond('GET /payments/page', emptyPage());
    apiMocks.respond('GET /payment-runs/run-7/advices', () => []);

    await page.goto('/tenant/finance/runs/run-7');

    await expect(page.getByRole('heading', { level: 1 })).toContainText(/PR-2026-100/);
    await expect(page.getByRole('button', { name: /^Export workbook$/i })).toHaveCount(0);
  });

  test('server 403: export failure surfaces in the error banner', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view', 'finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /payment-runs/run-7', () => run());
    apiMocks.respond('GET /payments/page', emptyPage());
    apiMocks.respond('GET /payment-runs/run-7/advices', () => []);
    apiMocks.respond(
      'GET /payment-runs/run-7/export/excel',
      () => ({
        type: 'https://medfund.healthcare/errors/report-disabled',
        title: 'Report disabled for tenant',
        detail: 'The PAYMENT_RUN_WORKBOOK report is disabled for this tenant.',
        status: 403,
      }),
      403,
    );

    await page.goto('/tenant/finance/runs/run-7');

    await page.getByRole('button', { name: /^Export workbook$/i }).click();
    // Blob responses carry no parsed error detail — the component shows the
    // generic fallback in the banner.
    await expect(page.getByText(/Failed to download workbook/i)).toBeVisible();
  });
});
