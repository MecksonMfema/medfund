import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 8 golden path — the 13-week cash-flow forecast
 * (cash-flow-forecast.component.ts) end to end:
 *
 *   1. Renders the per-currency weekly strip (inflow / outflow / net) from
 *      the stubbed envelope, plus the multi-currency net chart.
 *   2. Refetches with the new rollingWeeks when the selector changes — the
 *      request carries the picked window.
 *   3. Exports the workbook — `GET /reports/cash-flow-forecast/export/excel`.
 *   4. Shows the warnings banner when the envelope carries the
 *      finance-service-down warning.
 *
 * Every /api/v1 call is stubbed via ApiMocks — no live services needed.
 */

interface WeekBucket {
  weekStart: string;
  inflow: number;
  outflow: number;
  net: number;
}

interface CurrencySeries {
  currencyCode: string;
  totalInflow: number;
  totalOutflow: number;
  totalNet: number;
  buckets: WeekBucket[];
}

function envelope(series: CurrencySeries[], warnings: string[] = [], rollingWeeks = 13) {
  return {
    reportKey: 'CASH_FLOW_FORECAST_13W',
    period: { periodStart: '2026-08-14', periodEnd: '2026-11-13', grain: 'WEEKLY' },
    reportingCurrency: 'USD',
    data: {
      asOf: '2026-08-14',
      rollingWeeks,
      windowStart: '2026-08-14',
      windowEnd: '2026-11-13',
      series,
    },
    perCurrency: {
      USD: { totalAmount: 260, rowCount: 13 },
    },
    fxRates: {},
    warnings,
    generatedAt: '2026-08-15T10:00:00Z',
  };
}

function weeks(firstMonday: string, count: number, inflow: number, outflow: number): WeekBucket[] {
  const buckets: WeekBucket[] = [];
  const start = new Date(firstMonday);
  for (let i = 0; i < count; i++) {
    const ws = new Date(start.getTime() + i * 7 * 86400000);
    buckets.push({
      weekStart: ws.toISOString().slice(0, 10),
      inflow: i === 0 ? inflow : 0,
      outflow: i === 0 ? outflow : 0,
      net: (i === 0 ? inflow : 0) - (i === 0 ? outflow : 0),
    });
  }
  return buckets;
}

function reportCatalogue() {
  return [
    {
      id: null,
      tenantId: TENANT,
      reportKey: 'CASH_FLOW_FORECAST_13W',
      label: 'Cash-flow forecast',
      family: 'FORECAST',
      familyLabel: 'Forecast',
      enabled: true,
      cadenced: false,
      updatedAt: null,
      updatedBy: null,
    },
  ];
}

test.describe('Cash-flow forecast (Phase 8)', () => {
  test('golden path: render → refilter weeks → export → warnings banner', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());

    const usdBuckets = weeks('2026-08-10', 13, 100, 40);
    const zwlBuckets = weeks('2026-08-10', 13, 36500, 0);
    const series: CurrencySeries[] = [
      { currencyCode: 'USD', totalInflow: 100, totalOutflow: 40, totalNet: 60, buckets: usdBuckets },
      { currencyCode: 'ZWL', totalInflow: 36500, totalOutflow: 0, totalNet: 36500, buckets: zwlBuckets },
    ];

    const fetches: URLSearchParams[] = [];
    apiMocks.respond('GET /reports/cash-flow-forecast', (req) => {
      fetches.push(new URL(req.url()).searchParams);
      // Third fetch exercises the finance-service-down warnings banner.
      return envelope(series, fetches.length >= 3 ? ['payment-run-outflows unavailable — outflow reads as zero'] : []);
    });
    apiMocks.respond('GET /reports/cash-flow-forecast/export/excel', () => 'xlsx-stub');

    await page.goto('/tenant/finance/reports/cash-flow-forecast');
    // The lazy route chunk compiles on first navigation — allow for that.
    await expect(page.getByRole('heading', { name: 'Cash-flow forecast' })).toBeVisible({ timeout: 20_000 });

    // Pin the as-of date so the window is deterministic (change refires).
    await page.locator('input[name="asOf"]').fill('2026-08-14');
    await expect.poll(() => fetches.length).toBe(2);

    // Per-currency strips render with the KPI meta and the weekly net axis.
    await expect(page.getByRole('heading', { name: /^USD/ })).toBeVisible();
    await expect(page.getByRole('heading', { name: /^ZWL/ })).toBeVisible();
    await expect(page.getByText(/In 100\.00 · Out 40\.00 · Net 60\.00/)).toBeVisible();
    await expect(page.getByRole('cell', { name: '2026-08-10' })).toHaveCount(2);
    await expect(page.getByRole('cell', { name: '100.00' })).toBeVisible();
    await expect(page.getByRole('cell', { name: '40.00' })).toBeVisible();
    await expect(page.getByRole('cell', { name: '60.00' })).toBeVisible();

    // Two currencies → the shared net line chart renders.
    await expect(page.locator('app-line-chart')).toBeVisible();

    // Rolling-weeks refilter re-fires with the picked window.
    await page.locator('app-select[name="rollingWeeks"] .select-trigger').click();
    await page.getByRole('option', { name: /^26 weeks$/ }).click();
    await expect.poll(() => fetches.length).toBe(3);
    expect(fetches[2].get('rollingWeeks')).toBe('26');
    expect(fetches[2].get('asOf')).toBe('2026-08-14');

    // Third fetch returns the finance-down warning → banner appears.
    await page.locator('app-select[name="rollingWeeks"] .select-trigger').click();
    await page.getByRole('option', { name: /^13 weeks$/ }).click();
    await expect.poll(() => fetches.length).toBe(4);
    await expect(page.getByText(/outflow reads as zero/)).toBeVisible();

    // Export the workbook — the blob GET fires and resolves 200.
    const exportResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reports/cash-flow-forecast/export/excel') && r.request().method() === 'GET',
    );
    await page.getByRole('button', { name: /^Export Excel$/i }).click();
    expect((await exportResp).status()).toBe(200);
  });

  test('empty window: no-activity message instead of empty tables', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /reports/cash-flow-forecast', () => envelope([]));

    await page.goto('/tenant/finance/reports/cash-flow-forecast');
    await expect(page.getByText(/No invoices or payment-run activity in this window/)).toBeVisible({ timeout: 20_000 });
  });
});
