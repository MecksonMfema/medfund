import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 12 §C Phase 10 — Endorsement register report (ENDORSEMENT_REGISTER
 * key under ReportFamily.UNDERWRITING). Mirrors the §B report specs in
 * {@code underwriting-reports.spec.ts}: hub visibility, page render with
 * envelope + warnings, XLSX export blob, and disabled-toggle hides the
 * card. Every API call is stubbed.
 */

function reportCatalogue(enabled = true) {
  return [
    { id: null, tenantId: TENANT, reportKey: 'UPR_MOVEMENT',
      label: 'UPR movement', family: 'UNDERWRITING', familyLabel: 'Underwriting',
      enabled, cadenced: true, updatedAt: null, updatedBy: null },
    { id: null, tenantId: TENANT, reportKey: 'ENDORSEMENT_REGISTER',
      label: 'Endorsement register', family: 'UNDERWRITING', familyLabel: 'Underwriting',
      enabled, cadenced: false, updatedAt: null, updatedBy: null },
  ];
}

test.describe('Phase 12 §C endorsement register report', () => {
  test('reports hub shows the Endorsement register card when the key is enabled', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue(true));

    await page.goto('/tenant/finance/reports');
    await expect(page.getByRole('heading', { name: 'Underwriting' })).toBeVisible();
    await expect(page.getByText('Endorsement register')).toBeVisible();
  });

  test('endorsement register: renders row + warnings + fires export', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue(true));
    apiMocks.respond('GET /reports/premium/endorsements', () => ({
      reportKey: 'ENDORSEMENT_REGISTER',
      period: { periodStart: '2026-04-01', periodEnd: '2026-04-30', grain: 'MONTHLY' },
      reportingCurrency: 'USD',
      data: [
        {
          endorsementId: 'end-1',
          reference: 'END-2026-000042',
          policyId: 'pol-1',
          policySource: 'LIFE_POLICY',
          memberName: 'Jane Doe',
          insuranceLine: 'LIFE',
          changeType: 'PREMIUM_ADJUSTMENT',
          effectiveFrom: '2026-04-15',
          premiumDelta: '120.00',
          currencyCode: 'USD',
          status: 'COMMITTED',
          draftActorEmail: 'drafter@tenant',
          draftAt: '2026-04-10T10:00:00Z',
          approveActorEmail: 'supervisor@tenant',
          approveAt: '2026-04-11T09:00:00Z',
          commitActorEmail: 'supervisor@tenant',
          commitAt: '2026-04-11T09:05:00Z',
          voidedReason: null,
        },
      ],
      perCurrency: { USD: { totalAmount: 120, rowCount: 1 } },
      fxRates: {},
      warnings: ['FX not available for ZAR→USD as of 2026-08-23'],
      generatedAt: '2026-08-23T10:00:00Z',
    }));
    apiMocks.respond('GET /reports/premium/endorsements/export/excel', () => 'xlsx-stub');

    await page.goto('/tenant/finance/reports/underwriting/endorsement-register');
    await expect(page.getByRole('heading', { name: 'Endorsement register' })).toBeVisible();

    await expect(page.getByRole('cell', { name: 'END-2026-000042' })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'Jane Doe' })).toBeVisible();
    await expect(page.getByRole('cell', { name: 'COMMITTED' })).toBeVisible();
    await expect(page.getByText(/FX not available/i)).toBeVisible();

    const exportResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reports/premium/endorsements/export/excel')
        && r.request().method() === 'GET',
    );
    await page.getByRole('button', { name: /^Export Excel$/i }).click();
    expect((await exportResp).status()).toBe(200);
  });

  test('disabled report toggle → hub hides the endorsement register card', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue(false));

    await page.goto('/tenant/finance/reports');
    await expect(page.getByText('Endorsement register')).toHaveCount(0);
  });
});
