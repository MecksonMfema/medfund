import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 4 §B toggle round-trip — disabling a report in tenant-admin
 * Settings → Reports makes it vanish from the operational hub and its
 * APIs return 403 (the server-side @RequiresReport gate). Re-enabling
 * restores it.
 *
 * The 403 is simulated by overlaying the report endpoint stub — in the
 * e2e harness there is no live gateway to enforce the server gate.
 */

interface CatalogueRow {
  id: string | null;
  tenantId: string;
  reportKey: string;
  label: string;
  family: string | null;
  familyLabel: string | null;
  enabled: boolean;
  cadenced: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
}

function row(reportKey: string, label: string, enabled: boolean): CatalogueRow {
  return {
    id: null,
    tenantId: TENANT,
    reportKey,
    label,
    family: 'CLAIMS',
    familyLabel: 'Claims',
    enabled,
    cadenced: false,
    updatedAt: null,
    updatedBy: null,
  };
}

function seedCatalogue(): CatalogueRow[] {
  return [
    row('CLAIMS_SUMMARY', 'Claims — per scheme', true),
    row('HIGH_COST_CLAIMANT', 'High-cost claimants', true),
    row('PRE_AUTH_ACTIVITY', 'Pre-auth activity', true),
  ];
}

test.describe('Claims report toggles (Phase 4 §B)', () => {
  test('disable CLAIMS_SUMMARY in Settings → Reports: leaves hub, API 403s', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['admin:manage_settings', 'finance:view', 'finance:view_subledger'],
    });

    // Stateful catalogue — the PUT mutates what the GET returns so the
    // settings tab, the hub, and the direct-URL check stay consistent.
    const catalogue = seedCatalogue();
    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => catalogue);
    apiMocks.respond(`PUT /tenants/${TENANT}/report-config`, async (req) => {
      const body = JSON.parse(req.postData() ?? '{}') as { entries?: { reportKey: string; enabled: boolean }[] };
      for (const entry of body.entries ?? []) {
        const target = catalogue.find(r => r.reportKey === entry.reportKey);
        if (target) target.enabled = entry.enabled;
      }
      return catalogue;
    });
    // The Reports tab co-locates the high-cost threshold sub-form.
    apiMocks.respond(`GET /tenants/${TENANT}/high-cost-claimant-config`, () => ({
      tenantId: TENANT,
      thresholdAmount: '2000.00',
      currencyCode: 'USD',
      updatedAt: '2026-01-01T00:00:00Z',
      updatedBy: 'admin@acme.example',
    }));

    // Open Settings → Reports.
    await page.goto('/tenant/admin/settings');
    await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible();
    await page.getByRole('button', { name: /^Reports$/i }).click();

    // The row is on by default.
    const summarySwitch = page.locator('.switch-row', { hasText: 'CLAIMS_SUMMARY' });
    await expect(summarySwitch).toBeVisible();
    await expect(summarySwitch.locator('input[type="checkbox"]')).toBeChecked();

    // Turn it off and save — the PUT carries exactly the diff.
    await summarySwitch.locator('input[type="checkbox"]').uncheck();
    const putReq = page.waitForRequest(
      r => r.url().includes(`/api/v1/tenants/${TENANT}/report-config`) && r.method() === 'PUT',
    );
    await page.getByRole('button', { name: /^Save changes$/i }).click();
    const put = await putReq;
    expect(JSON.parse(put.postData() ?? '{}')).toEqual({
      entries: [{ reportKey: 'CLAIMS_SUMMARY', enabled: false }],
    });
    await expect(page.getByText('Saved')).toBeVisible();

    // The hub no longer lists the disabled report; siblings still show.
    await page.goto('/tenant/finance/reports');
    await expect(page.getByText('High-cost claimants')).toBeVisible();
    await expect(page.getByText('Pre-auth activity')).toBeVisible();
    await expect(page.getByText('Claims — per scheme')).toHaveCount(0);

    // Direct URL: the server-side @RequiresReport gate 403s the API and
    // the page surfaces the detail in its error banner.
    apiMocks.respond(
      'GET /reports/claims/schemes',
      () => ({
        type: 'https://medfund.healthcare/errors/report-disabled',
        title: 'Report disabled for tenant',
        detail: 'The CLAIMS_SUMMARY report is disabled for this tenant.',
        status: 403,
      }),
      403,
    );
    await page.goto('/tenant/finance/reports/claims-schemes');
    await expect(page.getByText(/disabled for this tenant/i)).toBeVisible();
  });
});
