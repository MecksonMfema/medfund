import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 13 §C Phase 10 — PROVIDER_NETWORK_UTILIZATION report (stays under
 * CLAIMS_FINANCIAL family per L8). Tests the two-level render + XLSX
 * export + tier filter.
 */

function reportCatalogue(enabled = true) {
  return [
    { id: null, tenantId: TENANT, reportKey: 'PROVIDER_NETWORK_UTILIZATION',
      label: 'Provider network utilization', family: 'CLAIMS_FINANCIAL',
      familyLabel: 'Claims Financial',
      enabled, cadenced: true, updatedAt: null, updatedBy: null },
  ];
}

function utilizationEnvelope() {
  return {
    reportKey: 'PROVIDER_NETWORK_UTILIZATION',
    period: { periodStart: '2026-06-01', periodEnd: '2026-06-30', grain: 'CUSTOM' },
    reportingCurrency: 'USD',
    data: {
      summary: {
        TIER_1: { networkTier: 'TIER_1', providerCount: 1, claimCount: 10,
                  totalClaimed: '1000.00', totalPaid: '800.00',
                  denialCount: 2, uniqueMembers: 5 },
        STANDARD: { networkTier: 'STANDARD', providerCount: 1, claimCount: 6,
                    totalClaimed: '600.00', totalPaid: '500.00',
                    denialCount: 1, uniqueMembers: 3 },
      },
      detail: [
        { providerId: 'p-1', providerName: 'Alpha Clinic', networkTier: 'TIER_1',
          insuranceLine: 'HEALTH', currencyCode: 'USD',
          claimCount: 10, totalClaimed: '1000.00', totalPaid: '800.00',
          denialCount: 2, uniqueMembers: 5 },
        { providerId: 'p-2', providerName: 'Beta Practice', networkTier: 'STANDARD',
          insuranceLine: 'HEALTH', currencyCode: 'USD',
          claimCount: 6, totalClaimed: '600.00', totalPaid: '500.00',
          denialCount: 1, uniqueMembers: 3 },
      ],
    },
    perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
  };
}

test.describe('Phase 13 §C provider network utilization', () => {
  test('page renders summary + per-provider detail tables', async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['operator'], permissions: ['finance:view_subledger'] });
    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue(true));
    apiMocks.respond('GET /reports/claims/provider-network-utilization',
        () => utilizationEnvelope());

    await page.goto('/tenant/finance/reports/claims/provider-network-utilization');
    await expect(page.getByRole('heading', { name: 'Provider network utilization' })).toBeVisible();
    await expect(page.getByText('TIER_1')).toBeVisible();
    await expect(page.getByText('Alpha Clinic')).toBeVisible();
    await expect(page.getByText('Beta Practice')).toBeVisible();
  });

  test('XLSX export button fires a download', async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['operator'], permissions: ['finance:view_subledger'] });
    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue(true));
    apiMocks.respond('GET /reports/claims/provider-network-utilization',
        () => utilizationEnvelope());
    apiMocks.respondBlob('GET /reports/claims/provider-network-utilization/export',
        () => new TextEncoder().encode('x'));

    await page.goto('/tenant/finance/reports/claims/provider-network-utilization');
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: /Export XLSX/i }).click();
    await download;
  });

  test('peer-down warning surfaces on the page', async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['operator'], permissions: ['finance:view_subledger'] });
    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue(true));
    apiMocks.respond('GET /reports/claims/provider-network-utilization', () => ({
      ...utilizationEnvelope(),
      warnings: ['provider metadata unavailable for 2 providers'],
    }));

    await page.goto('/tenant/finance/reports/claims/provider-network-utilization');
    await expect(page.getByText('provider metadata unavailable for 2 providers')).toBeVisible();
  });
});
