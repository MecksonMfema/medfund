import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 12 §B — Underwriting reports (UPR movement, premium register,
 * new business register). All three land under
 * {@code /tenant/finance/reports/underwriting/*}; every API call is
 * stubbed via ApiMocks — no live services needed.
 *
 * Assertions per page:
 *   1. Page renders the envelope's rows and per-currency KPI cards.
 *   2. Envelope warnings surface as a banner.
 *   3. The XLSX export button fires the /export/excel GET.
 */

function reportCatalogue() {
  return [
    { id: null, tenantId: TENANT, reportKey: 'UPR_MOVEMENT',
      label: 'UPR movement', family: 'UNDERWRITING', familyLabel: 'Underwriting',
      enabled: true, cadenced: true, updatedAt: null, updatedBy: null },
    { id: null, tenantId: TENANT, reportKey: 'PREMIUM_REGISTER',
      label: 'Premium register', family: 'UNDERWRITING', familyLabel: 'Underwriting',
      enabled: true, cadenced: false, updatedAt: null, updatedBy: null },
    { id: null, tenantId: TENANT, reportKey: 'NEW_BUSINESS_REGISTER',
      label: 'New business register', family: 'UNDERWRITING', familyLabel: 'Underwriting',
      enabled: true, cadenced: false, updatedAt: null, updatedBy: null },
  ];
}

test.describe('Phase 12 §B underwriting reports', () => {
  test('reports hub shows Underwriting family card when the three keys are enabled', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());

    await page.goto('/tenant/finance/reports');
    await expect(page.getByRole('heading', { name: 'Underwriting' })).toBeVisible();
    await expect(page.getByText('UPR movement')).toBeVisible();
    await expect(page.getByText('Premium register')).toBeVisible();
    await expect(page.getByText('New business register')).toBeVisible();
  });

  test('UPR movement: renders row + warnings + fires export', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /reports/premium/upr-movement', () => ({
      reportKey: 'UPR_MOVEMENT',
      period: { periodStart: '2026-01-01', periodEnd: '2026-03-31', grain: 'QUARTERLY' },
      reportingCurrency: 'USD',
      data: [
        {
          insuranceLine: 'LIFE', currencyCode: 'USD',
          openingUpr: '0.00', writtenPremium: '1200.00',
          earnedPremium: '300.00', endorsementDelta: '0.00',
          closingUpr: '900.00',
        },
      ],
      perCurrency: { USD: { totalAmount: 900, rowCount: 1 } },
      fxRates: {},
      warnings: ['FX not available for ZAR→USD as of 2026-08-23'],
      generatedAt: '2026-08-23T10:00:00Z',
    }));
    apiMocks.respond('GET /reports/premium/upr-movement/export/excel', () => 'xlsx-stub');

    await page.goto('/tenant/finance/reports/underwriting/upr-movement');
    await expect(page.getByRole('heading', { name: 'UPR movement' })).toBeVisible();

    await expect(page.getByRole('cell', { name: 'Life Insurance' })).toBeVisible();
    await expect(page.getByRole('cell', { name: '900.00' })).toBeVisible();
    await expect(page.getByText(/FX not available/i)).toBeVisible();

    const exportResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reports/premium/upr-movement/export/excel')
        && r.request().method() === 'GET',
    );
    await page.getByRole('button', { name: /^Export Excel$/i }).click();
    expect((await exportResp).status()).toBe(200);
  });

  test('Premium register: renders row + NB badge + fires export', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /reports/premium/register', () => ({
      reportKey: 'PREMIUM_REGISTER',
      period: { periodStart: '2026-01-01', periodEnd: '2026-01-31', grain: 'MONTHLY' },
      reportingCurrency: 'USD',
      data: [
        {
          policyId: 'p-1', policySource: 'LIFE_POLICY',
          memberName: 'Jane Doe', insuranceLine: 'LIFE',
          schemeName: 'Term Life 2026', currencyCode: 'USD',
          writtenPremium: '100.00', earnedInPeriod: '100.00',
          unearnedAtPeriodEnd: '0.00',
          boundAt: '2026-01-01T00:00:00Z',
          coverageStart: '2026-01-01', coverageEnd: '2026-12-31',
          isNewBusiness: true,
          portfolioName: 'MISC', cohortName: 'MISC-2026-DEFAULT',
          periodStart: '2026-01-01', periodEnd: '2026-01-31',
        },
      ],
      perCurrency: { USD: { totalAmount: 100, rowCount: 1 } },
      fxRates: {},
      warnings: [],
      generatedAt: '2026-08-23T10:00:00Z',
    }));
    apiMocks.respond('GET /reports/premium/register/export/excel', () => 'xlsx-stub');

    await page.goto('/tenant/finance/reports/underwriting/premium-register');
    await expect(page.getByRole('heading', { name: 'Premium register' })).toBeVisible();

    await expect(page.getByRole('cell', { name: 'Jane Doe' })).toBeVisible();
    await expect(page.getByRole('cell', { name: /Term Life 2026/ })).toBeVisible();
    await expect(page.getByText('NB').first()).toBeVisible();

    const exportResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reports/premium/register/export/excel')
        && r.request().method() === 'GET',
    );
    await page.getByRole('button', { name: /^Export Excel$/i }).click();
    expect((await exportResp).status()).toBe(200);
  });

  test('New business register: renders member row + fires export', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /reports/premium/new-business', () => ({
      reportKey: 'NEW_BUSINESS_REGISTER',
      period: { periodStart: '2026-01-01', periodEnd: '2026-03-31', grain: 'QUARTERLY' },
      reportingCurrency: 'USD',
      data: [
        {
          policyId: 'p-2', policySource: 'VEHICLE_POLICY',
          memberNumber: 'M-0001', memberName: 'John Smith',
          insuranceLine: 'VEHICLE', schemeName: 'Comprehensive Motor',
          boundAt: '2026-02-15T09:00:00Z',
          writtenPremium: '2400.00', currencyCode: 'USD',
          portfolioName: 'General Motor', cohortName: 'GM-2026-NON-ONEROUS',
          coverageStart: '2026-02-15', coverageEnd: '2027-02-14',
        },
      ],
      perCurrency: { USD: { totalAmount: 2400, rowCount: 1 } },
      fxRates: {},
      warnings: [],
      generatedAt: '2026-08-23T10:00:00Z',
    }));
    apiMocks.respond('GET /reports/premium/new-business/export/excel', () => 'xlsx-stub');

    await page.goto('/tenant/finance/reports/underwriting/new-business-register');
    await expect(page.getByRole('heading', { name: 'New business register' })).toBeVisible();

    await expect(page.getByRole('cell', { name: 'John Smith' })).toBeVisible();
    await expect(page.getByRole('cell', { name: /Comprehensive Motor/ })).toBeVisible();
    await expect(page.getByRole('cell', { name: /2026-02-15/ })).toBeVisible();

    const exportResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reports/premium/new-business/export/excel')
        && r.request().method() === 'GET',
    );
    await page.getByRole('button', { name: /^Export Excel$/i }).click();
    expect((await exportResp).status()).toBe(200);
  });

  test('disabled report toggle → hub hides the family card', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue().map(r => ({ ...r, enabled: false })));

    await page.goto('/tenant/finance/reports');
    await expect(page.getByRole('heading', { name: 'Underwriting' })).toHaveCount(0);
  });
});
