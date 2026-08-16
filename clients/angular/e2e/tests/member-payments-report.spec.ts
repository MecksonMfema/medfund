import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 5 §B — MEMBER_PAYMENTS_UNIFIED report end to end:
 *
 *   1. Golden path — renders rows (billed / received / claims-paid / net
 *      position) from the stubbed envelope, refetches on period change,
 *      and exports the workbook.
 *   2. Toggle round-trip — disabling MEMBER_PAYMENTS_UNIFIED in
 *      Settings → Reports makes it vanish from the operational hub and its
 *      API 403s (the server-side @RequiresReport gate) with the page
 *      surfacing the detail in its error banner.
 *
 * Every /api/v1 call is stubbed via ApiMocks — no live services needed.
 */

interface MemberPaymentRow {
  memberId: string;
  memberName: string;
  currencyCode: string;
  totalBilled: string;
  totalReceived: string;
  totalClaimsPaid: string;
  netPosition: string;
}

function envelope(rows: MemberPaymentRow[]) {
  return {
    reportKey: 'MEMBER_PAYMENTS_UNIFIED',
    period: { periodStart: '2026-07-01', periodEnd: '2026-07-31', grain: 'CUSTOM' },
    reportingCurrency: 'USD',
    data: { periodStart: '2026-07-01', periodEnd: '2026-07-31', rows },
    perCurrency: {
      USD: {
        totalAmount: rows.reduce((sum, r) => sum + Number(r.netPosition), 0),
        rowCount: rows.length,
      },
    },
    fxRates: {},
    warnings: [],
    generatedAt: '2026-08-15T10:00:00Z',
  };
}

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
    family: 'RECONCILIATION',
    familyLabel: 'Reconciliation',
    enabled,
    cadenced: false,
    updatedAt: null,
    updatedBy: null,
  };
}

function seedCatalogue(): CatalogueRow[] {
  return [
    row('LOSS_RATIO', 'Loss ratio (billing vs claims)', true),
    row('MEMBER_PAYMENTS_UNIFIED', 'Member payments — unified', true),
  ];
}

test.describe('Member payments report (Phase 5 §B)', () => {
  test('golden path: render → period refilter → export', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => seedCatalogue());

    const rows: MemberPaymentRow[] = [
      {
        memberId: 'm-1', memberName: 'Alice Ncube', currencyCode: 'USD',
        totalBilled: '200.00', totalReceived: '130.00', totalClaimsPaid: '70.00', netPosition: '60.00',
      },
      {
        memberId: 'm-2', memberName: 'Bob Dube', currencyCode: 'USD',
        totalBilled: '100.00', totalReceived: '100.00', totalClaimsPaid: '80.00', netPosition: '20.00',
      },
    ];

    const fetches: URLSearchParams[] = [];
    apiMocks.respond('GET /reports/member-payments', (req) => {
      fetches.push(new URL(req.url()).searchParams);
      return envelope(rows);
    });
    // Blob endpoint — a 200 string is enough; the UI only downloads it.
    apiMocks.respond('GET /reports/member-payments/export/excel', () => 'xlsx-stub');

    await page.goto('/tenant/finance/reports/member-payments');
    await expect(page.getByRole('heading', { name: 'Member payments' })).toBeVisible();

    // Rows render from the stub envelope, incl. the computed net position.
    await expect(page.getByRole('cell', { name: /Alice Ncube/ })).toBeVisible();
    await expect(page.getByRole('cell', { name: /Bob Dube/ })).toBeVisible();
    await expect(page.getByRole('cell', { name: /60.00/ })).toBeVisible();
    await expect(page.getByRole('cell', { name: /20.00/ })).toBeVisible();

    // Re-filter by period window — re-fires the request with new params.
    await page.locator('input[name="periodStart"]').fill('2026-06-01');
    await page.locator('input[name="periodEnd"]').fill('2026-06-30');
    await expect.poll(() => fetches.length).toBe(3);
    expect(fetches[1].get('periodStart')).toBe('2026-06-01');
    expect(fetches[2].get('periodEnd')).toBe('2026-06-30');

    // Export the workbook — the blob GET fires and resolves 200.
    const exportResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reports/member-payments/export/excel') && r.request().method() === 'GET',
    );
    await page.getByRole('button', { name: /^Export Excel$/i }).click();
    expect((await exportResp).status()).toBe(200);
  });

  test('disable MEMBER_PAYMENTS_UNIFIED in Settings → Reports: leaves hub, API 403s', async ({ page, apiMocks, signInAs }) => {
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
    const memberPaymentsSwitch = page.locator('.switch-row', { hasText: 'MEMBER_PAYMENTS_UNIFIED' });
    await expect(memberPaymentsSwitch).toBeVisible();
    await expect(memberPaymentsSwitch.locator('input[type="checkbox"]')).toBeChecked();

    // Turn it off and save — the PUT carries exactly the diff.
    await memberPaymentsSwitch.locator('input[type="checkbox"]').uncheck();
    const putReq = page.waitForRequest(
      r => r.url().includes(`/api/v1/tenants/${TENANT}/report-config`) && r.method() === 'PUT',
    );
    await page.getByRole('button', { name: /^Save changes$/i }).click();
    const put = await putReq;
    expect(JSON.parse(put.postData() ?? '{}')).toEqual({
      entries: [{ reportKey: 'MEMBER_PAYMENTS_UNIFIED', enabled: false }],
    });
    await expect(page.getByText('Saved')).toBeVisible();

    // The hub no longer lists the disabled report; the sibling still shows.
    await page.goto('/tenant/finance/reports');
    await expect(page.getByText('Loss ratio (billing vs claims)')).toBeVisible();
    await expect(page.getByText('Member payments — unified')).toHaveCount(0);

    // Direct URL: the server-side @RequiresReport gate 403s the API and
    // the page surfaces the detail in its error banner.
    apiMocks.respond(
      'GET /reports/member-payments',
      () => ({
        type: 'https://medfund.healthcare/errors/report-disabled',
        title: 'Report disabled for tenant',
        detail: 'The MEMBER_PAYMENTS_UNIFIED report is disabled for this tenant.',
        status: 403,
      }),
      403,
    );
    await page.goto('/tenant/finance/reports/member-payments');
    await expect(page.getByText(/disabled for this tenant/i)).toBeVisible();
  });
});
