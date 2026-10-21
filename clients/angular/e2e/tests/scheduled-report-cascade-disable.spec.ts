import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 17 §D.1 — cascade-disable UX.
 *
 * When the tenant admin disables a report on Settings → Reports and there are
 * active schedules for that report, a confirm modal warns them how many
 * scheduled deliveries will pause. Confirm → the PUT fires. Cancel → nothing.
 *
 * Server-side, the tenancy-service cascade-disables the schedules; here we
 * assert the UI behavior + payload only.
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
  activeScheduleCount?: number;
}

function seedCatalogue(): CatalogueRow[] {
  return [
    {
      id: null, tenantId: TENANT,
      reportKey: 'COMMISSION_STATEMENT',
      label: 'Commission statement',
      family: 'COMMISSION', familyLabel: 'Commission',
      enabled: true, cadenced: true,
      updatedAt: null, updatedBy: null,
      activeScheduleCount: 2,
    },
    {
      id: null, tenantId: TENANT,
      reportKey: 'LOSS_RATIO',
      label: 'Loss ratio',
      family: 'RECONCILIATION', familyLabel: 'Reconciliation',
      enabled: true, cadenced: true,
      updatedAt: null, updatedBy: null,
      activeScheduleCount: 0,
    },
    {
      id: null, tenantId: TENANT,
      reportKey: 'PRE_AUTH_ACTIVITY',
      label: 'Pre-auth activity',
      family: 'CLAIMS', familyLabel: 'Claims',
      enabled: true, cadenced: false,
      updatedAt: null, updatedBy: null,
    },
  ];
}

async function openReportsTab(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/tenant/admin/settings');
  await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible();
  await page.getByRole('button', { name: /^Reports$/i }).click();
}

test.describe('Scheduled report cascade-disable modal (Phase 17 §D.1)', () => {
  test('disable a report with active schedules → confirm modal → PUT fires', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: [
        'admin:manage_settings',
        'tenant.settings:manage_report_schedules',
      ],
    });

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
    apiMocks.respond(`GET /tenants/${TENANT}/high-cost-claimant-config`, () => ({
      tenantId: TENANT,
      thresholdAmount: '2000.00',
      currencyCode: 'USD',
      updatedAt: '2026-01-01T00:00:00Z',
      updatedBy: 'admin@acme.example',
    }));

    await openReportsTab(page);

    const commissionSwitch = page.locator('.switch-row', { hasText: 'COMMISSION_STATEMENT' });
    await expect(commissionSwitch).toBeVisible();
    await commissionSwitch.locator('input[type="checkbox"]').uncheck();

    // No PUT until the modal is confirmed. Assert no request has fired yet.
    let putFiredEarly = false;
    page.on('request', (r) => {
      if (r.url().includes(`/api/v1/tenants/${TENANT}/report-config`) && r.method() === 'PUT') {
        putFiredEarly = true;
      }
    });

    await page.getByRole('button', { name: /^Save changes$/i }).click();

    // Modal — the ConfirmDialog uses a role="dialog" wrapper.
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('Pause scheduled deliveries?');
    // 1 report × 2 schedules → "1 report" + "2 scheduled schedules".
    await expect(dialog).toContainText(/2 scheduled/);
    expect(putFiredEarly).toBe(false);

    const putReq = page.waitForRequest(
      r => r.url().includes(`/api/v1/tenants/${TENANT}/report-config`) && r.method() === 'PUT',
    );
    await dialog.getByRole('button', { name: /^Disable & pause$/i }).click();
    const put = await putReq;
    expect(JSON.parse(put.postData() ?? '{}')).toEqual({
      entries: [{ reportKey: 'COMMISSION_STATEMENT', enabled: false }],
    });
    await expect(page.getByText('Saved')).toBeVisible();
  });

  test('cancel on the modal → no PUT fires', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['admin:manage_settings'],
    });

    const catalogue = seedCatalogue();
    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => catalogue);
    apiMocks.respond(`GET /tenants/${TENANT}/high-cost-claimant-config`, () => ({
      tenantId: TENANT, thresholdAmount: '2000.00', currencyCode: 'USD',
      updatedAt: '2026-01-01T00:00:00Z', updatedBy: 'admin@acme.example',
    }));

    let putHit = false;
    apiMocks.respond(`PUT /tenants/${TENANT}/report-config`, () => {
      putHit = true;
      return catalogue;
    });

    await openReportsTab(page);
    const commissionSwitch = page.locator('.switch-row', { hasText: 'COMMISSION_STATEMENT' });
    await commissionSwitch.locator('input[type="checkbox"]').uncheck();
    await page.getByRole('button', { name: /^Save changes$/i }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: /^Keep enabled$/i }).click();
    await expect(dialog).toBeHidden();

    // Give any inflight request a chance to appear — we should see none.
    await page.waitForTimeout(200);
    expect(putHit).toBe(false);
  });
});
