import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 13 §C Phase 10 — POLICY_LIFECYCLE report family (POLICY_MOVEMENT,
 * PERSISTENCY_COHORT, GROUP_CENSUS). Mirrors the endorsement register
 * spec pattern: hub visibility, page render with envelope, XLSX export
 * fires, disabled-toggle hides the family card. All API calls stubbed.
 */

function reportCatalogue(enabled = true) {
  return [
    { id: null, tenantId: TENANT, reportKey: 'POLICY_MOVEMENT',
      label: 'Policy movement', family: 'POLICY_LIFECYCLE', familyLabel: 'Policy lifecycle',
      enabled, cadenced: true, updatedAt: null, updatedBy: null },
    { id: null, tenantId: TENANT, reportKey: 'PERSISTENCY_COHORT',
      label: 'Persistency cohort', family: 'POLICY_LIFECYCLE', familyLabel: 'Policy lifecycle',
      enabled, cadenced: true, updatedAt: null, updatedBy: null },
    { id: null, tenantId: TENANT, reportKey: 'GROUP_CENSUS',
      label: 'Group census', family: 'POLICY_LIFECYCLE', familyLabel: 'Policy lifecycle',
      enabled, cadenced: true, updatedAt: null, updatedBy: null },
  ];
}

test.describe('Phase 13 §C policy-lifecycle reports', () => {
  test('hub shows Policy lifecycle family card when any key enabled', async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['operator'], permissions: ['finance:view'] });
    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue(true));

    await page.goto('/tenant/finance/reports');
    await expect(page.getByRole('heading', { name: 'Policy lifecycle' })).toBeVisible();
    await expect(page.getByText('Policy movement')).toBeVisible();
    await expect(page.getByText('Persistency cohort')).toBeVisible();
    await expect(page.getByText('Group census')).toBeVisible();
  });

  test('policy movement page renders + fires XLSX export', async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['operator'], permissions: ['finance:view_subledger'] });
    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue(true));
    apiMocks.respond('GET /reports/policy-lifecycle/movement', () => ({
      reportKey: 'POLICY_MOVEMENT',
      period: { periodStart: '2026-07-01', periodEnd: '2026-07-31', grain: 'CUSTOM' },
      reportingCurrency: 'USD',
      data: {
        rows: [{
          policySource: 'LIFE_POLICY', insuranceLine: 'LIFE', currencyCode: 'USD',
          openingCount: 100, newBusinessCount: 5, renewedCount: 2,
          lapsedCount: 1, terminatedCount: 3, closingCount: 103,
          writtenPremiumAdded: '500.00', writtenPremiumRemoved: '150.00',
        }],
      },
      perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    }));
    apiMocks.respondBlob('GET /reports/policy-lifecycle/movement/export',
        () => new TextEncoder().encode('x'));

    await page.goto('/tenant/finance/reports/policy-lifecycle/movement');
    await expect(page.getByRole('heading', { name: 'Policy movement' })).toBeVisible();
    await expect(page.getByText('LIFE_POLICY')).toBeVisible();

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: /Export XLSX/i }).click();
    await download;
  });

  test('persistency cohort page renders + surfaces freshness warning', async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['operator'], permissions: ['finance:view_subledger'] });
    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue(true));
    apiMocks.respond('GET /reports/policy-lifecycle/persistency-cohort', () => ({
      reportKey: 'PERSISTENCY_COHORT',
      period: null,
      reportingCurrency: 'USD',
      data: {
        rows: [{
          cohortMonth: '2024-01-01', insuranceLine: 'HEALTH',
          checkpointMonths: 12, cohortSize: 100, stillActive: 82,
          retentionRate: '82.00',
        }],
        freshnessWarning: 'HEALTH persistency data may be up to 30 hours stale',
      },
      perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    }));

    await page.goto('/tenant/finance/reports/policy-lifecycle/persistency-cohort');
    await expect(page.getByRole('heading', { name: 'Persistency cohort' })).toBeVisible();
    await expect(page.getByText('HEALTH persistency data may be up to 30 hours stale')).toBeVisible();
    await expect(page.getByText('82.00%')).toBeVisible();
  });

  test('group census page renders + exports', async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['operator'], permissions: ['finance:view_subledger'] });
    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue(true));
    apiMocks.respond('GET /reports/policy-lifecycle/group-census', () => ({
      reportKey: 'GROUP_CENSUS',
      period: null,
      reportingCurrency: 'USD',
      data: {
        asOf: '2026-08-01',
        groups: [{
          groupId: 'g-1', groupName: 'Acme Ltd', registrationNumber: 'REG-123',
          contactPerson: 'Alice', contactEmail: 'alice@acme',
          activeMembers: 12, suspendedMembers: 1, lapsedMembers: 0,
          terminatedMembers: 3, totalMembers: 16,
        }],
      },
      perCurrency: {}, fxRates: {}, warnings: [], generatedAt: '',
    }));
    apiMocks.respondBlob('GET /reports/policy-lifecycle/group-census/export',
        () => new TextEncoder().encode('x'));

    await page.goto('/tenant/finance/reports/policy-lifecycle/group-census');
    await expect(page.getByRole('heading', { name: 'Group census' })).toBeVisible();
    await expect(page.getByText('Acme Ltd')).toBeVisible();

    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: /Export XLSX/i }).click();
    await download;
  });

  test('disabled toggle hides Policy lifecycle family card', async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['operator'], permissions: ['finance:view'] });
    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue(false));

    await page.goto('/tenant/finance/reports');
    await expect(page.getByRole('heading', { name: 'Policy lifecycle' })).toHaveCount(0);
  });
});
