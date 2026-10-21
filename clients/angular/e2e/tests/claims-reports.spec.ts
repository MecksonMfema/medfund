import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 4 §B golden path — the per-scheme CLAIMS_SUMMARY report
 * (scheme-claims-report.component.ts) end to end:
 *
 *   1. Renders rows + per-currency KPI strip from the stubbed envelope.
 *   2. Refetches with the new window when the period start/end changes.
 *   3. Refetches with `insuranceLine=HEALTH` when the line filter changes.
 *   4. Exports the workbook — `GET /reports/claims/schemes/export/excel`.
 *   5. Drills into a scheme → monthly buckets + claim ledger + detail export.
 *
 * Every /api/v1 call is stubbed via ApiMocks — no live services needed.
 */

interface ClaimsSummaryRow {
  dimensionId: string;
  dimensionName: string;
  insuranceLine: string | null;
  currencyCode: string;
  claimCount: number;
  totalClaimed: string;
  totalApproved: string;
  totalPaid: string;
}

interface ClaimsLedgerRow {
  id: string;
  claimNumber: string;
  memberName: string;
  providerName: string;
  submissionDate: string;
  serviceDate: string;
  adjudicatedAt: string;
  status: string;
  rejectionCode: string;
  claimedAmount: string;
  approvedAmount: string;
  paidAmount: string;
  currencyCode: string;
}

function summaryEnvelope(rows: ClaimsSummaryRow[]) {
  return {
    reportKey: 'CLAIMS_SUMMARY',
    period: { periodStart: '2026-07-01', periodEnd: '2026-07-31', grain: 'CUSTOM' },
    reportingCurrency: 'USD',
    data: rows,
    perCurrency: {
      USD: {
        totalAmount: rows.reduce((sum, r) => sum + Number(r.totalPaid), 0),
        rowCount: rows.length,
      },
    },
    fxRates: {},
    warnings: [],
    generatedAt: '2026-08-15T10:00:00Z',
  };
}

function detailEnvelope() {
  return {
    reportKey: 'CLAIMS_SUMMARY',
    period: { periodStart: '2026-06-01', periodEnd: '2026-06-30', grain: 'CUSTOM' },
    reportingCurrency: 'USD',
    data: {
      dimensionId: 'sch-1',
      dimensionName: 'Acme Unified',
      monthlyBuckets: [
        {
          month: '2026-06-01',
          currencyCode: 'USD',
          claimCount: 12,
          totalClaimed: '12000.00',
          totalApproved: '10000.00',
          totalPaid: '8000.00',
        },
      ],
      claims: {
        content: [
          {
            id: 'clm-101',
            claimNumber: 'CLM-101',
            memberName: 'Alice Ncube',
            providerName: 'Harare Medical Centre',
            submissionDate: '2026-06-18T10:00:00Z',
            serviceDate: '2026-06-15',
            adjudicatedAt: '2026-06-20T10:00:00Z',
            status: 'PAID',
            rejectionCode: '',
            claimedAmount: '120.00',
            approvedAmount: '110.00',
            paidAmount: '110.00',
            currencyCode: 'USD',
          },
        ] satisfies ClaimsLedgerRow[],
        total: 1,
        page: 0,
        size: 50,
        totalPages: 1,
      },
    },
    perCurrency: { USD: { totalAmount: 8000, rowCount: 1 } },
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
      reportKey: 'CLAIMS_SUMMARY',
      label: 'Claims — per scheme',
      family: 'CLAIMS',
      familyLabel: 'Claims',
      enabled: true,
      cadenced: false,
      updatedAt: null,
      updatedBy: null,
    },
    {
      id: null,
      tenantId: TENANT,
      reportKey: 'HIGH_COST_CLAIMANT',
      label: 'High-cost claimants',
      family: 'CLAIMS',
      familyLabel: 'Claims',
      enabled: true,
      cadenced: false,
      updatedAt: null,
      updatedBy: null,
    },
    {
      id: null,
      tenantId: TENANT,
      reportKey: 'PRE_AUTH_ACTIVITY',
      label: 'Pre-auth activity',
      family: 'CLAIMS',
      familyLabel: 'Claims',
      enabled: true,
      cadenced: false,
      updatedAt: null,
      updatedBy: null,
    },
  ];
}

test.describe('Claims financial reports (Phase 4 §B)', () => {
  test('per-scheme golden path: render → period → line filter → export → drill', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());

    const schemesRows: ClaimsSummaryRow[] = [
      { dimensionId: 'sch-1', dimensionName: 'Acme Unified',  insuranceLine: null, currencyCode: 'USD', claimCount: 12, totalClaimed: '12000.00', totalApproved: '10000.00', totalPaid: '8000.00' },
      { dimensionId: 'sch-2', dimensionName: 'Hilltop Medical', insuranceLine: null, currencyCode: 'USD', claimCount: 3, totalClaimed: '2400.00', totalApproved: '2200.00', totalPaid: '2100.00' },
    ];

    // Record every summary fetch so we can assert the refilter params.
    const summaryFetches: URLSearchParams[] = [];
    apiMocks.respond('GET /reports/claims/schemes', (req) => {
      summaryFetches.push(new URL(req.url()).searchParams);
      return summaryEnvelope(schemesRows);
    });
    apiMocks.respond('GET /reports/claims/schemes/:id', () => detailEnvelope());
    // Blob endpoints — a 200 string is enough; the UI only downloads it.
    apiMocks.respond('GET /reports/claims/schemes/export/excel', () => 'xlsx-stub');
    apiMocks.respond('GET /reports/claims/schemes/:id/export/excel', () => 'xlsx-stub');

    await page.goto('/tenant/finance/reports/claims-schemes');
    await expect(page.getByRole('heading', { name: 'Claims report — per scheme' })).toBeVisible();

    // Rows render from the stub envelope.
    await expect(page.getByRole('cell', { name: /Acme Unified/ })).toBeVisible();
    await expect(page.getByRole('cell', { name: /Hilltop Medical/ })).toBeVisible();

    // Re-filter by period window.
    await page.locator('input[name="periodStart"]').fill('2026-06-01');
    await page.locator('input[name="periodEnd"]').fill('2026-06-30');

    // Re-filter by insurance line.
    await page.locator('app-select[name="insuranceLine"] .select-trigger').click();
    await page.locator('.select-option .option-label', { hasText: /^Health Insurance$/ }).click();

    // The three interactions each refetched with the right params.
    await expect.poll(() => summaryFetches.length).toBe(4);
    expect(summaryFetches[1].get('periodStart')).toBe('2026-06-01');
    expect(summaryFetches[2].get('periodEnd')).toBe('2026-06-30');
    expect(summaryFetches[3].get('insuranceLine')).toBe('HEALTH');
    expect(summaryFetches[3].get('reportingCurrency')).toBe('USD');

    // Export the workbook — the blob GET fires and resolves 200.
    const exportResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reports/claims/schemes/export/excel') && r.request().method() === 'GET',
    );
    await page.getByRole('button', { name: /^Export Excel$/i }).click();
    expect((await exportResp).status()).toBe(200);

    // Drill into the first scheme — monthly buckets + claim ledger.
    await page.getByRole('cell', { name: /Acme Unified/ }).click();
    await expect(page.getByRole('heading', { name: 'Scheme claims — Acme Unified' })).toBeVisible();
    await expect(page.getByText('Monthly buckets')).toBeVisible();
    await expect(page.getByRole('cell', { name: 'Jun 2026', exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: /CLM-101/ })).toBeVisible();
    await expect(page.getByRole('cell', { name: /Alice Ncube/ })).toBeVisible();

    // Detail export also fires.
    const detailExportResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reports/claims/schemes/sch-1/export/excel') && r.request().method() === 'GET',
    );
    await page.getByRole('button', { name: /^Export Excel$/i }).click();
    expect((await detailExportResp).status()).toBe(200);
  });
});
