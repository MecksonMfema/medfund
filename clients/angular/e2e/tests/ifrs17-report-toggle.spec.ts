import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 15 §21 toggle round-trip — disabling IFRS17_LRC_LIC_RECONCILIATION
 * in tenant-admin Settings → Reports removes the card from the reports
 * hub, and the report page's submit surface receives a server-side 403
 * (the {@code @RequiresReport} gate on the backend controller).
 *
 * Mirrors {@code claims-reports-toggle.spec.ts} — the 403 is simulated
 * by overlaying the POST stub on the direct-URL step, since there's no
 * live gateway to enforce the server gate in the e2e harness.
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

function row(reportKey: string, label: string, enabled: boolean, family = 'REGULATORY'): CatalogueRow {
  return {
    id: null,
    tenantId: TENANT,
    reportKey,
    label,
    family,
    familyLabel: family === 'REGULATORY' ? 'Regulatory' : family,
    enabled,
    cadenced: family === 'REGULATORY',
    updatedAt: null,
    updatedBy: null,
  };
}

function seedCatalogue(): CatalogueRow[] {
  return [
    row('IFRS17_LRC_LIC_RECONCILIATION', 'IFRS 17 — LRC / LIC reconciliation', true),
    row('IFRS17_INSURANCE_REVENUE_SERVICE_RESULT',
        'IFRS 17 — insurance revenue & service result', true),
    row('IBNR_TRIANGLE', 'IBNR triangle', true, 'ACTUARIAL'),
  ];
}

test.describe('IFRS 17 report toggles (Phase 15 §21)', () => {
  test('disable IFRS17_LRC_LIC_RECONCILIATION: leaves hub, submit 403s on direct URL', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['admin:manage_settings', 'finance:view', 'finance:view_subledger'],
    });

    // Stateful catalogue — the PUT mutates what the GET returns.
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

    // Toggle IFRS17_LRC_LIC_RECONCILIATION off.
    const lrcSwitch = page.locator('.switch-row', { hasText: 'IFRS17_LRC_LIC_RECONCILIATION' });
    await expect(lrcSwitch).toBeVisible();
    await expect(lrcSwitch.locator('input[type="checkbox"]')).toBeChecked();

    await lrcSwitch.locator('input[type="checkbox"]').uncheck();
    const putReq = page.waitForRequest(
      r => r.url().includes(`/api/v1/tenants/${TENANT}/report-config`) && r.method() === 'PUT',
    );
    await page.getByRole('button', { name: /^Save changes$/i }).click();
    const put = await putReq;
    expect(JSON.parse(put.postData() ?? '{}')).toEqual({
      entries: [{ reportKey: 'IFRS17_LRC_LIC_RECONCILIATION', enabled: false }],
    });
    await expect(page.getByText('Saved')).toBeVisible();

    // Hub no longer shows the disabled report; sibling IFRS 17 revenue survives.
    await page.goto('/tenant/finance/reports');
    await expect(page.getByText('IFRS 17 — insurance revenue & service result')).toBeVisible();
    await expect(page.getByText('IFRS 17 — LRC / LIC reconciliation')).toHaveCount(0);

    // Direct URL: the backend's @RequiresReport gate 403s the submit; the
    // report page surfaces the detail in its error banner.
    apiMocks.respond(
      'POST /reports/ifrs17/lrc-lic-reconciliation',
      () => ({
        type: 'https://medfund.healthcare/errors/report-disabled',
        title: 'Report disabled for tenant',
        detail: 'The IFRS17_LRC_LIC_RECONCILIATION report is disabled for this tenant.',
        status: 403,
      }),
      403,
    );
    await page.goto('/tenant/finance/reports/ifrs17/lrc-lic-reconciliation');
    await page.getByRole('button', { name: /^Run report$/ }).click();
    await expect(page.getByText(/disabled for this tenant/i)).toBeVisible();
  });
});
