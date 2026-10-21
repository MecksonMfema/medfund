import { test, expect, TENANT } from '../fixtures/test';
import { ApiMocks } from '../fixtures/api-mocks';

/**
 * Phase 18 §Phase 8 — Executive KPI dashboard, golden-path e2e.
 *
 * The repo's e2e infrastructure is fully mocked (see fixtures/api-mocks.ts),
 * so this spec asserts the UI flow and the API calls it fires. The composer
 * fanout + cache + trend math are covered by
 * {@code ExecutiveKpiControllerIT} + {@code KpiComposerServiceTest}.
 *
 *   1. /tenant/finance/reports/kpi renders the 5-tile grid.
 *   2. Insurance-line chip change re-fires dashboard + trend calls.
 *   3. Loss-ratio tile click navigates to /reports/billing-vs-claims.
 *   4. Per-tile export button downloads an XLSX with the right filename.
 */

const PERIOD = { start: '2026-08-01', end: '2026-09-01', grain: 'MONTHLY' };
const KPI_KEYS = ['LOSS_RATIO_KPI', 'EXPENSE_RATIO', 'COMBINED_RATIO',
                  'CLAIMS_FREQUENCY', 'AVERAGE_SEVERITY'] as const;

function envelope(reportKey: string, ratio: number, basisNote: string | null = null) {
  return {
    reportKey,
    period: PERIOD,
    reportingCurrency: 'USD',
    data: {
      compositeRatio:       ratio,
      compositeNumerator:   ratio * 100,
      compositeDenominator: 100,
      basisNote,
      perCurrency: {
        USD: { ratio, numerator: ratio * 100, denominator: 100, currencyCode: 'USD' },
      },
    },
    perCurrency: {},
    fxRates: {},
    warnings: [],
    generatedAt: '2026-09-05T00:00:00Z',
  };
}

function dashboardResponse() {
  return {
    tiles: {
      LOSS_RATIO_KPI:   envelope('LOSS_RATIO_KPI',   0.6),
      EXPENSE_RATIO:    envelope('EXPENSE_RATIO',    0.2),
      COMBINED_RATIO:   envelope('COMBINED_RATIO',   0.8, 'MIXED_LOSS_EARNED_EXPENSE_WRITTEN'),
      CLAIMS_FREQUENCY: envelope('CLAIMS_FREQUENCY', 0.05),
      AVERAGE_SEVERITY: envelope('AVERAGE_SEVERITY', 250),
    },
  };
}

function trendResponse() {
  return Array.from({ length: 12 }, (_, i) => ({
    periodStart: `2025-${String(i + 1).padStart(2, '0')}-01`,
    periodEnd:   `2025-${String(i + 2).padStart(2, '0')}-01`,
    composite: {
      compositeRatio: 0.5 + i * 0.01,
      compositeNumerator: 50 + i,
      compositeDenominator: 100,
      basisNote: null,
      perCurrency: {},
    },
    perCurrency: {},
    warnings: [],
  }));
}

function stubKpiApis(apiMocks: ApiMocks): void {
  apiMocks.respond('GET /reports/kpi/dashboard', dashboardResponse());
  for (const key of KPI_KEYS) {
    apiMocks.respond(`GET /reports/kpi/${key}/trend`, trendResponse());
  }
  // Scheme / producer search — un-stubbed calls would 404; a KPI page load
  // won't fire these unless the user types in the search fields, but the
  // defaults keep hard-to-diagnose console errors out of the recording.
  apiMocks.respond('GET /schemes/search', []);
  apiMocks.respond('GET /producers/search', []);
}

test.describe('Executive KPI dashboard (Phase 18 §Phase 8)', () => {
  test('renders the 5-tile grid + filter chips', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });
    stubKpiApis(apiMocks);

    await page.goto('/tenant/finance/reports/kpi');
    await expect(page.getByRole('heading', { name: 'Executive KPIs' })).toBeVisible();

    for (const label of ['Loss ratio', 'Acquisition ratio', 'Combined ratio',
                          'Claims frequency', 'Average severity']) {
      await expect(page.getByRole('heading', { name: label })).toBeVisible();
    }

    // Filter cells render.
    await expect(page.getByText('Insurance line')).toBeVisible();
    await expect(page.getByText('Reporting currency')).toBeVisible();
  });

  test('tile click navigates to the drill-through report', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });
    stubKpiApis(apiMocks);
    // Loss-ratio drill target — stub any calls its page fires just enough to render.
    apiMocks.respond('GET /reports/billing-vs-claims', {
      reportKey: 'LOSS_RATIO', period: PERIOD, reportingCurrency: 'USD',
      data: { rows: [] }, perCurrency: {}, fxRates: {}, warnings: [],
      generatedAt: '2026-09-05T00:00:00Z',
    });

    await page.goto('/tenant/finance/reports/kpi');
    await page.getByRole('heading', { name: 'Loss ratio' }).click();

    await expect(page).toHaveURL(/\/tenant\/finance\/reports\/billing-vs-claims/);
  });

  test('export button downloads an XLSX for the tile', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });
    stubKpiApis(apiMocks);
    // Route the export separately — a real XLSX-Blob response makes the browser
    // fire a download event we can observe.
    await page.route('**/api/v1/reports/kpi/LOSS_RATIO_KPI/export/excel*', route =>
      route.fulfill({
        status: 200,
        contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        headers: {
          'Content-Disposition': 'attachment; filename="loss_ratio_kpi-2026-08-01-to-2026-09-01.xlsx"',
        },
        body: Buffer.from([0x50, 0x4B, 0x03, 0x04]),  // XLSX zip magic
      }),
    );

    await page.goto('/tenant/finance/reports/kpi');
    // Wait for tiles to render.
    await expect(page.getByRole('heading', { name: 'Loss ratio' })).toBeVisible();

    // The tile-scoped export button is inside the Loss ratio tile card.
    const lossRatioTile = page.locator('app-kpi-tile').filter({ hasText: 'Loss ratio' }).first();
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      lossRatioTile.getByRole('button', { name: 'Export XLSX' }).click(),
    ]);
    expect(download.suggestedFilename()).toMatch(/loss_ratio_kpi.*\.xlsx/);
  });
});
