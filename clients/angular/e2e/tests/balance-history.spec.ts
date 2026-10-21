import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 6 §B golden path — the provider/member balance-history reports
 * (reports/balance-history, V080 freeze-frames) end to end:
 *
 *   1. Renders rows from the stubbed envelope, newest-first, incl. the
 *      per-currency KPI strip (latest frozen outstanding).
 *   2. Refetches when the currency / as-at-run filters change — the request
 *      carries the picked params.
 *   3. Exports the workbook — `GET /reports/balance-history/provider/:id/export/excel`.
 *   4. Server-side @RequiresReport gate 403 → the disabled-report detail in
 *      the error banner.
 *   5. Missing `finance:view_subledger` → the guard redirects to /unauthorized.
 *
 * Every /api/v1 call is stubbed via ApiMocks — no live services needed.
 */

interface BalanceHistoryRow {
  runId: string;
  runNumber: string;
  executedAt: string;
  currencyCode: string;
  openingBalance: string;
  closingBalance: string;
  totalClaimed: string;
  totalApproved: string;
  totalPaid: string;
  netDue: string;
}

function envelope(rows: BalanceHistoryRow[], payeeName = 'Acme Medical Centre') {
  return {
    reportKey: 'PROVIDER_BALANCE_HISTORY',
    period: null,
    reportingCurrency: '',
    data: {
      payeeId: 'prov-1',
      payeeName,
      rows,
    },
    perCurrency: {
      USD: { totalAmount: 2500, rowCount: 2 },
      ZAR: { totalAmount: 800, rowCount: 1 },
    },
    fxRates: {},
    warnings: [],
    generatedAt: '2026-08-15T10:00:00Z',
  };
}

/** Minimal report-config catalogue so the operational sidebar resolves cleanly. */
function reportCatalogue() {
  return [
    {
      id: null,
      tenantId: TENANT,
      reportKey: 'PROVIDER_BALANCE_HISTORY',
      label: 'Provider balance history',
      family: 'RECONCILIATION',
      familyLabel: 'Reconciliation',
      enabled: true,
      cadenced: false,
      updatedAt: null,
      updatedBy: null,
    },
    {
      id: null,
      tenantId: TENANT,
      reportKey: 'MEMBER_BALANCE_HISTORY',
      label: 'Member balance history',
      family: 'RECONCILIATION',
      familyLabel: 'Reconciliation',
      enabled: true,
      cadenced: false,
      updatedAt: null,
      updatedBy: null,
    },
  ];
}

const rows: BalanceHistoryRow[] = [
  {
    runId: 'run-2', runNumber: 'PR-2026-002', executedAt: '2026-08-01T10:00:00Z',
    currencyCode: 'USD', openingBalance: '2500.00', closingBalance: '2500.00',
    totalClaimed: '4000.00', totalApproved: '3500.00', totalPaid: '3000.00', netDue: '500.00',
  },
  {
    runId: 'run-2', runNumber: 'PR-2026-002', executedAt: '2026-08-01T10:00:00Z',
    currencyCode: 'ZAR', openingBalance: '800.00', closingBalance: '800.00',
    totalClaimed: '1200.00', totalApproved: '1000.00', totalPaid: '600.00', netDue: '400.00',
  },
  {
    runId: 'run-1', runNumber: 'PR-2026-001', executedAt: '2026-07-01T09:00:00Z',
    currencyCode: 'USD', openingBalance: '1900.00', closingBalance: '1900.00',
    totalClaimed: '3000.00', totalApproved: '2600.00', totalPaid: '2100.00', netDue: '500.00',
  },
];

test.describe('Balance history (Phase 6 §B)', () => {
  test('golden path: provider rows render → refilter → export', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    // The as-at-run selector is fed by the executed-runs list.
    apiMocks.respond('GET /payment-runs', () => [
      { id: 'run-1', runNumber: 'PR-2026-001', status: 'executed', payeeType: 'PROVIDER',
        totalAmount: '2600.00', currencyCode: 'USD', paymentCount: 1 },
      { id: 'run-2', runNumber: 'PR-2026-002', status: 'executed', payeeType: 'PROVIDER',
        totalAmount: '900.00', currencyCode: 'USD', paymentCount: 2 },
      { id: 'run-3', runNumber: 'PR-2026-003', status: 'draft', payeeType: 'PROVIDER',
        totalAmount: '0.00', currencyCode: 'USD', paymentCount: 0 },
    ]);

    const fetches: URLSearchParams[] = [];
    apiMocks.respond('GET /reports/balance-history/provider/:id', (req) => {
      fetches.push(new URL(req.url()).searchParams);
      return envelope(rows);
    });
    apiMocks.respond('GET /reports/balance-history/provider/:id/export/excel', () => 'xlsx-stub');

    await page.goto('/tenant/finance/reports/balance-history/provider/prov-1');
    await expect(page.getByRole('heading', { name: 'Provider balance history' })).toBeVisible();
    await expect(page.getByText(/Acme Medical Centre/)).toBeVisible();

    // Rows render newest-first from the stub envelope.
    await expect(page.getByRole('cell', { name: /PR-2026-002/ })).toHaveCount(2);
    await expect(page.getByRole('cell', { name: /PR-2026-001/ })).toHaveCount(1);
    await expect(page.getByRole('cell', { name: '3,500.00' })).toBeVisible();
    await expect(page.getByRole('cell', { name: '500.00', exact: true })).toHaveCount(2);

    // Per-currency KPI strip shows the latest frozen outstanding.
    await expect(page.getByText(/Latest outstanding — USD/)).toBeVisible();
    const usdKpi = page.locator('.kpi-card', { hasText: 'Latest outstanding — USD' });
    await expect(usdKpi.getByText('2,500.00')).toBeVisible();
    await expect(page.getByText(/Latest outstanding — ZAR/)).toBeVisible();

    // Currency filter re-fires the request with the picked code.
    await page.locator('app-select[name="currency"] .select-trigger').click();
    await page.getByRole('option', { name: /^USD$/ }).click();
    await expect.poll(() => fetches.length).toBe(2);
    expect(fetches[1].get('currency')).toBe('USD');
    expect(fetches[1].get('asAtRun')).toBeNull();

    // As-at-run filter re-fires with the run id.
    await page.locator('app-select[name="asAtRun"] .select-trigger').click();
    await page.getByRole('option', { name: /PR-2026-002/ }).click();
    await expect.poll(() => fetches.length).toBe(3);
    expect(fetches[2].get('asAtRun')).toBe('run-2');
    expect(fetches[2].get('currency')).toBe('USD');

    // Export the workbook — the blob GET fires and resolves 200.
    const exportResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reports/balance-history/provider/prov-1/export/excel') && r.request().method() === 'GET',
    );
    await page.getByRole('button', { name: /^Export Excel$/i }).click();
    expect((await exportResp).status()).toBe(200);
  });

  test('server gate 403: disabled report shows the detail in the banner', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /payment-runs', () => []);
    apiMocks.respond(
      'GET /reports/balance-history/provider/:id',
      () => ({
        type: 'https://medfund.healthcare/errors/report-disabled',
        title: 'Report disabled for tenant',
        detail: 'The PROVIDER_BALANCE_HISTORY report is disabled for this tenant.',
        status: 403,
      }),
      403,
    );

    await page.goto('/tenant/finance/reports/balance-history/provider/prov-1');
    await expect(page.getByText(/disabled for this tenant/i)).toBeVisible();
  });

  test('permission guard: missing finance:view_subledger redirects to 403', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view'],
    });

    await page.goto('/tenant/finance/reports/balance-history/provider/prov-1');
    await expect(page.getByText('403 — Unauthorized')).toBeVisible();
  });
});
