import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 5 §B golden path — the LOSS_RATIO report
 * (loss-ratio-report.component.ts) end to end:
 *
 *   1. Renders rows from the stubbed envelope, incl. the paid-ratio cell and
 *      the "—" dash when a scheme billed nothing for the window.
 *   2. Refetches with the new window when the period start/end changes.
 *   3. Exports the workbook — `GET /reports/billing-vs-claims/export/excel`.
 *   4. Shows the warnings banner when the envelope carries a warnings array.
 *
 * Every /api/v1 call is stubbed via ApiMocks — no live services needed.
 */

interface LossRatioRow {
  schemeId: string;
  schemeName: string;
  currencyCode: string;
  totalBilled: string;
  totalClaimed: string;
  totalApproved: string;
  totalPaid: string;
  paidRatioPct: string | null;
  billedMinusPaid: string;
}

function envelope(rows: LossRatioRow[], warnings: string[] = []) {
  return {
    reportKey: 'LOSS_RATIO',
    period: { periodStart: '2026-07-01', periodEnd: '2026-07-31', grain: 'CUSTOM' },
    reportingCurrency: 'USD',
    data: { periodStart: '2026-07-01', periodEnd: '2026-07-31', rows },
    perCurrency: {
      USD: {
        totalAmount: rows.reduce((sum, r) => sum + Number(r.totalPaid), 0),
        rowCount: rows.length,
      },
    },
    fxRates: {},
    warnings,
    generatedAt: '2026-08-15T10:00:00Z',
  };
}

/** Minimal report-config catalogue so the operational sidebar resolves cleanly. */
function reportCatalogue() {
  return [
    {
      id: null,
      tenantId: TENANT,
      reportKey: 'LOSS_RATIO',
      label: 'Loss ratio (billing vs claims)',
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
      reportKey: 'MEMBER_PAYMENTS_UNIFIED',
      label: 'Member payments — unified',
      family: 'RECONCILIATION',
      familyLabel: 'Reconciliation',
      enabled: true,
      cadenced: false,
      updatedAt: null,
      updatedBy: null,
    },
  ];
}

test.describe('Loss ratio report (Phase 5 §B)', () => {
  test('golden path: render → period refilter → export → warnings banner', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());

    const rows: LossRatioRow[] = [
      {
        schemeId: 'sch-1', schemeName: 'Acme Unified', currencyCode: 'USD',
        totalBilled: '10000.00', totalClaimed: '9000.00', totalApproved: '7500.00',
        totalPaid: '6666.67', paidRatioPct: '66.67', billedMinusPaid: '3333.33',
      },
      {
        schemeId: 'sch-2', schemeName: 'Hilltop Medical', currencyCode: 'USD',
        totalBilled: '0.00', totalClaimed: '120.00', totalApproved: '100.00',
        totalPaid: '100.00', paidRatioPct: null, billedMinusPaid: '-100.00',
      },
    ];

    // Record every fetch so we can assert the refilter params. The third
    // fetch returns warnings to exercise the banner without a second test.
    const fetches: URLSearchParams[] = [];
    apiMocks.respond('GET /reports/billing-vs-claims', (req) => {
      fetches.push(new URL(req.url()).searchParams);
      return envelope(rows, fetches.length >= 3 ? ['billing-aggregate[SCHEME] is using partial data'] : []);
    });
    // Blob endpoint — a 200 string is enough; the UI only downloads it.
    apiMocks.respond('GET /reports/billing-vs-claims/export/excel', () => 'xlsx-stub');

    await page.goto('/tenant/finance/reports/billing-vs-claims');
    await expect(page.getByRole('heading', { name: 'Loss ratio' })).toBeVisible();

    // Rows render from the stub envelope.
    await expect(page.getByRole('cell', { name: /Acme Unified/ })).toBeVisible();
    await expect(page.getByRole('cell', { name: /Hilltop Medical/ })).toBeVisible();

    // Paid-ratio cell shows the computed value; null ratio renders the dash.
    await expect(page.getByRole('cell', { name: '66.67%' })).toBeVisible();
    await expect(page.getByRole('cell', { name: '—' })).toBeVisible();
    await expect(page.getByRole('cell', { name: /3,333.33/ })).toBeVisible();

    // Re-filter by period window — re-fires the request with new params.
    await page.locator('input[name="periodStart"]').fill('2026-06-01');
    await page.locator('input[name="periodEnd"]').fill('2026-06-30');
    await expect.poll(() => fetches.length).toBe(3);
    expect(fetches[1].get('periodStart')).toBe('2026-06-01');
    expect(fetches[2].get('periodEnd')).toBe('2026-06-30');
    expect(fetches[2].get('reportingCurrency')).toBe('USD');

    // Warnings banner surfaces the partial-data note.
    await expect(page.getByText(/partial data/i)).toBeVisible();

    // Export the workbook — the blob GET fires and resolves 200.
    const exportResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reports/billing-vs-claims/export/excel') && r.request().method() === 'GET',
    );
    await page.getByRole('button', { name: /^Export Excel$/i }).click();
    expect((await exportResp).status()).toBe(200);
  });
});
