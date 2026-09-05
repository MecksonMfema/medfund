import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 18 §Phase 8 — KPI scheduled-email creation flow. Follows the same
 * mocked convention as {@code scheduled-report-happy-path.spec.ts}: we assert
 * the UI flow + API call shape; the actual probe→orchestrator→SMTP round-trip
 * is covered by tenancy-service + finance-service integration tests.
 *
 * The load-bearing bit: the whitelist widening in
 * {@link com.medfund.shared.report.ScheduledReportEligibility} must have
 * happened, or the POST 400s. Angular renders whatever the catalogue reports
 * as {@code cadenced=true} without an additional client-side filter.
 */

const NEW_SCHEDULE_ID = '55555555-5555-5555-5555-555555555555';

function kpiCatalogueRow(reportKey: string, label: string) {
  return {
    id: null, tenantId: TENANT,
    reportKey, label,
    family: 'DASHBOARD', familyLabel: 'Executive KPIs',
    enabled: true, cadenced: true,
    updatedAt: null, updatedBy: null,
    activeScheduleCount: 0,
  };
}

test.describe('KPI scheduled email delivery (Phase 18 §Phase 8)', () => {
  test('LOSS_RATIO_KPI appears in the "Add a schedule" candidates and creates a monthly schedule', async ({
    page, apiMocks, signInAs,
  }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: [
        'admin:manage_settings',
        'tenant.settings:manage_report_schedules',
        'tenant.settings:tenant_admin',
      ],
    });

    const catalogue = [
      kpiCatalogueRow('LOSS_RATIO_KPI',   'Loss ratio (KPI)'),
      kpiCatalogueRow('EXPENSE_RATIO',    'Acquisition ratio'),
      kpiCatalogueRow('COMBINED_RATIO',   'Combined ratio'),
      kpiCatalogueRow('CLAIMS_FREQUENCY', 'Claims frequency (KPI)'),
      kpiCatalogueRow('AVERAGE_SEVERITY', 'Average severity (KPI)'),
    ];
    const schedules: unknown[] = [];

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => catalogue);
    apiMocks.respond(`GET /tenants/${TENANT}/report-schedules`, () => schedules);

    apiMocks.respond(`POST /tenants/${TENANT}/report-schedules`, async (req: import('@playwright/test').Request) => {
      const body = JSON.parse(req.postData() ?? '{}');
      const created = {
        id: NEW_SCHEDULE_ID,
        tenantId: TENANT,
        reportKey: body.reportKey,
        reportLabel: 'Loss ratio (KPI)',
        enabled: body.enabled,
        cadence: body.cadence,
        hourOfDay: body.hourOfDay,
        dayOfWeek: body.dayOfWeek ?? null,
        dayOfMonth: body.dayOfMonth ?? null,
        reportingCurrency: null,
        lastFiredAt: null,
        lastStatus: null,
        createdAt: '2026-09-01T00:00:00Z',
        updatedAt: '2026-09-01T00:00:00Z',
        recipients: [],
      };
      schedules.push(created);
      const target = catalogue.find(r => r.reportKey === body.reportKey);
      if (target) target.activeScheduleCount = 1;
      return created;
    }, 201);

    await page.goto('/tenant/admin/settings/report-schedules');
    await expect(page.getByRole('heading', { name: 'Report schedules', exact: true })).toBeVisible();

    // All 5 KPI keys appear as candidates (whitelist widening confirmed via UI surface).
    for (const label of ['Loss ratio (KPI)', 'Acquisition ratio', 'Combined ratio',
                          'Claims frequency (KPI)', 'Average severity (KPI)']) {
      await expect(page.locator('.candidate', { hasText: label })).toBeVisible();
    }

    // Create a monthly schedule for LOSS_RATIO_KPI.
    const lossKpiCand = page.locator('.candidate', { hasText: 'Loss ratio (KPI)' });
    await lossKpiCand.getByRole('button', { name: /^Create schedule$/ }).click();

    const createReq = page.waitForRequest(
      r => r.url().endsWith(`/api/v1/tenants/${TENANT}/report-schedules`) && r.method() === 'POST',
    );
    await lossKpiCand.getByRole('button', { name: /^Create$/ }).click();
    const posted = await createReq;
    expect(JSON.parse(posted.postData() ?? '{}')).toMatchObject({
      reportKey: 'LOSS_RATIO_KPI',
      cadence: 'MONTHLY',
      enabled: true,
    });

    await expect(page.getByText('Loss ratio (KPI)').first()).toBeVisible();
  });
});
