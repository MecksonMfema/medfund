import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 10 §A — Reinsurance bordereau reports (cession, recoveries,
 * treaty utilization). Golden export path for each:
 *
 *   1. Page renders the envelope's rows and per-currency KPI cards.
 *   2. Envelope warnings surface as a banner.
 *   3. The XLSX export button fires the /export/excel GET.
 *
 * Every /api/v1 call is stubbed via ApiMocks — no live services needed.
 */

interface Reinsurer {
  id: string; name: string; active: boolean;
  createdAt: string; updatedAt: string;
}

interface Treaty {
  id: string; treatyRef: string; treatyType: string; declaredCurrency: string;
  inceptionDate: string; expiryDate: string; status: string;
  renewedFromTreatyId: string | null;
  aggregateLimit: number | null; aggregateLimitCurrency: string | null;
  expectedAnnualPremium: number | null; producerRef: string | null;
  activatedAt: string | null; createdAt: string; updatedAt: string;
}

function seededReinsurer(id: string, name: string): Reinsurer {
  return {
    id, name, active: true,
    createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
  };
}

function seededTreaty(id: string, ref: string, type = 'QUOTA_SHARE'): Treaty {
  return {
    id, treatyRef: ref, treatyType: type, declaredCurrency: 'USD',
    inceptionDate: '2026-01-01', expiryDate: '2026-12-31', status: 'ACTIVE',
    renewedFromTreatyId: null,
    aggregateLimit: null, aggregateLimitCurrency: null,
    expectedAnnualPremium: null, producerRef: null,
    activatedAt: '2026-01-01T00:00:00Z',
    createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
  };
}

function reportCatalogue() {
  return [
    { id: null, tenantId: TENANT, reportKey: 'REINSURANCE_CESSION_BORDEREAU',
      label: 'Cession bordereau', family: 'REINSURANCE', familyLabel: 'Reinsurance',
      enabled: true, cadenced: false, updatedAt: null, updatedBy: null },
    { id: null, tenantId: TENANT, reportKey: 'REINSURANCE_RECOVERIES',
      label: 'Recoveries bordereau', family: 'REINSURANCE', familyLabel: 'Reinsurance',
      enabled: true, cadenced: false, updatedAt: null, updatedBy: null },
    { id: null, tenantId: TENANT, reportKey: 'REINSURANCE_TREATY_UTILIZATION',
      label: 'Treaty utilization', family: 'REINSURANCE', familyLabel: 'Reinsurance',
      enabled: true, cadenced: false, updatedAt: null, updatedBy: null },
  ];
}

test.describe('Reinsurance bordereau reports (§A)', () => {
  test('cession bordereau: render + warnings banner + export click', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance.reinsurance:view'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /reinsurance/reinsurers', () => ({
      content: [seededReinsurer('re-1', 'Munich Re')],
      total: 1, page: 0, size: 200, totalPages: 1,
    }));
    apiMocks.respond('GET /reinsurance/treaties', () => ({
      content: [seededTreaty('t-1', 'HEALTH-QS-2026')],
      total: 1, page: 0, size: 200, totalPages: 1,
    }));

    apiMocks.respond('GET /reports/reinsurance/cession-bordereau', () => ({
      reportKey: 'REINSURANCE_CESSION_BORDEREAU',
      period: { periodStart: '2026-07-01', periodEnd: '2026-10-01', grain: 'QUARTERLY' },
      reportingCurrency: 'USD',
      data: [
        {
          cessionId: 'ce-1', treatyId: 't-1', treatyRef: 'HEALTH-QS-2026',
          treatyType: 'QUOTA_SHARE', reinsurerId: 're-1', reinsurerName: 'Munich Re',
          sharePct: '60.0000', shareRole: 'LEADER', cessionType: 'LOSS',
          source: 'AUTOMATIC', occurredAt: '2026-07-15T10:00:00Z',
          sourceEventId: 'cl-1', sourceEventType: 'CLAIM_ADJUDICATED',
          nativeBasis: '10000.00', nativeCededFull: '3000.00', participantCeded: '1800.00',
          currencyCode: 'USD', createdAt: '2026-07-15T10:05:00Z',
          priorPeriodAdjustment: false,
        },
      ],
      perCurrency: { USD: { totalAmount: '1800.00', rowCount: 1 } },
      fxRates: {},
      warnings: ['FX not available for ZAR→USD as of 2026-08-22'],
      generatedAt: '2026-08-22T10:00:00Z',
    }));
    apiMocks.respond('GET /reports/reinsurance/cession-bordereau/export/excel', () => 'xlsx-stub');

    await page.goto('/tenant/finance/reports/reinsurance/cession-bordereau');
    await expect(page.getByRole('heading', { name: 'Cession bordereau' })).toBeVisible();

    // Row renders from stub envelope.
    await expect(page.getByRole('cell', { name: 'Munich Re' })).toBeVisible();
    await expect(page.getByRole('cell', { name: /HEALTH-QS-2026/ })).toBeVisible();
    await expect(page.getByRole('cell', { name: /1,800.00/ })).toBeVisible();

    // Warnings banner surfaces the FX note.
    await expect(page.getByText(/FX not available/i)).toBeVisible();

    // Export blob fires with 200.
    const exportResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reports/reinsurance/cession-bordereau/export/excel')
        && r.request().method() === 'GET',
    );
    await page.getByRole('button', { name: /^Export Excel$/i }).click();
    expect((await exportResp).status()).toBe(200);
  });

  test('recoveries bordereau: renders status column, reload after export', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance.reinsurance:view'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /reinsurance/reinsurers', () => ({
      content: [seededReinsurer('re-1', 'Munich Re')],
      total: 1, page: 0, size: 200, totalPages: 1,
    }));
    apiMocks.respond('GET /reinsurance/treaties', () => ({
      content: [seededTreaty('t-1', 'HEALTH-QS-2026')],
      total: 1, page: 0, size: 200, totalPages: 1,
    }));

    let recoveryStatus = 'EXPECTED';
    apiMocks.respond('GET /reports/reinsurance/recoveries-bordereau', () => ({
      reportKey: 'REINSURANCE_RECOVERIES',
      period: { periodStart: '2026-07-01', periodEnd: '2026-10-01', grain: 'QUARTERLY' },
      reportingCurrency: 'USD',
      data: [
        {
          recoveryId: 'rc-1', cessionId: 'ce-1', treatyId: 't-1', treatyRef: 'HEALTH-QS-2026',
          reinsurerId: 're-1', reinsurerName: 'Munich Re',
          sharePct: '60.0000', status: recoveryStatus,
          sourceEventId: 'cl-1', occurredAt: '2026-07-15T10:00:00Z',
          nativeExpected: '3000.00', nativeReceived: null,
          participantExpected: '1800.00', participantReceived: null,
          currencyCode: 'USD',
          invoicedAt: recoveryStatus === 'INVOICED' ? '2026-08-22T10:00:00Z' : null,
          receivedAt: null, writeOffReason: null,
          createdAt: '2026-07-15T10:05:00Z',
          priorPeriodAdjustment: false,
        },
      ],
      perCurrency: { USD: { totalAmount: '1800.00', rowCount: 1 } },
      fxRates: {}, warnings: [],
      generatedAt: '2026-08-22T10:00:00Z',
    }));
    // Export flips the (mocked) recovery status to INVOICED so the follow-up
    // GET the component fires shows the transition.
    apiMocks.respond('GET /reports/reinsurance/recoveries-bordereau/export/excel', () => {
      recoveryStatus = 'INVOICED';
      return 'xlsx-stub';
    });

    await page.goto('/tenant/finance/reports/reinsurance/recoveries-bordereau');
    await expect(page.getByRole('heading', { name: 'Recoveries bordereau' })).toBeVisible();

    // Initial status is EXPECTED.
    await expect(page.getByText('EXPECTED', { exact: true })).toBeVisible();

    const exportResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reports/reinsurance/recoveries-bordereau/export/excel')
        && r.request().method() === 'GET',
    );
    await page.getByRole('button', { name: /^Export Excel$/i }).click();
    expect((await exportResp).status()).toBe(200);

    // Post-export refetch shows the transitioned status.
    await expect(page.getByText('INVOICED', { exact: true })).toBeVisible();
  });

  test('treaty utilization: renders per-layer row + utilization %', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance.reinsurance:view'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /reinsurance/treaties', () => ({
      content: [seededTreaty('t-1', 'HEALTH-XOL-2026', 'EXCESS_OF_LOSS')],
      total: 1, page: 0, size: 200, totalPages: 1,
    }));

    apiMocks.respond('GET /reports/reinsurance/treaty-utilization', () => ({
      reportKey: 'REINSURANCE_TREATY_UTILIZATION',
      period: null,
      reportingCurrency: 'USD',
      data: [
        {
          treatyId: 't-1', treatyRef: 'HEALTH-XOL-2026', treatyType: 'EXCESS_OF_LOSS',
          aggregateLimit: '1000000.00', aggregateLimitCurrency: 'USD',
          inceptionDate: '2026-01-01', expiryDate: '2026-12-31',
          layerId: 'l-1', layerOrder: 1,
          retention: '10000.00', layerLimit: '90000.00', layerCurrency: 'USD',
          cededCurrency: 'USD',
          totalCededNative: '22500.00', cessionCount: 3,
        },
      ],
      perCurrency: { USD: { totalAmount: '22500.00', rowCount: 1 } },
      fxRates: {}, warnings: [],
      generatedAt: '2026-08-22T10:00:00Z',
    }));

    await page.goto('/tenant/finance/reports/reinsurance/treaty-utilization');
    await expect(page.getByRole('heading', { name: 'Treaty utilization' })).toBeVisible();

    // Treaty gets auto-selected on load, so the row renders.
    await expect(page.getByRole('cell', { name: /HEALTH-XOL-2026/ })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'Layer 1' })).toBeVisible();
    await expect(page.getByRole('cell', { name: /22,500.00/ })).toBeVisible();
    // 22500 / 90000 = 25.00%
    await expect(page.getByRole('cell', { name: /25.00%/ })).toBeVisible();
  });
});
