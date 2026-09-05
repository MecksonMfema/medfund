import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 17 §D.1 — scheduled report delivery, golden path.
 *
 * The plan's ideal-world version drives a real backend + mailpit; this repo's
 * e2e infrastructure is fully mocked (see fixtures/api-mocks.ts), so the spec
 * asserts the UI flow and the API calls it fires. The end-to-end wiring
 * (probe → orchestrator → notification-service → SMTP) is covered by the
 * per-service integration tests already landed in earlier phases.
 *
 *   1. Open /tenant/admin/settings/report-schedules → schedules render.
 *   2. Create a MONTHLY commission_statement schedule → POST fires, list re-fetches.
 *   3. Add a second recipient → POST /recipients fires.
 *   4. Open Run history → GET /runs fires.
 *   5. Click Download on a completed run → GET /download fires.
 *   6. Click Re-run → POST /rerun fires, list re-loads.
 */

const SCHEDULE_ID = '22222222-2222-2222-2222-222222222222';
const NEW_SCHEDULE_ID = '33333333-3333-3333-3333-333333333333';
const JOB_ID = '44444444-4444-4444-4444-444444444444';

function reportCatalogue() {
  return [
    {
      id: null, tenantId: TENANT,
      reportKey: 'COMMISSION_STATEMENT',
      label: 'Commission statement',
      family: 'COMMISSION', familyLabel: 'Commission',
      enabled: true, cadenced: true,
      updatedAt: null, updatedBy: null,
      activeScheduleCount: 0,
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
  ];
}

function baseSchedule() {
  return {
    id: SCHEDULE_ID,
    tenantId: TENANT,
    reportKey: 'COMMISSION_STATEMENT',
    reportLabel: 'Commission statement',
    enabled: true,
    cadence: 'MONTHLY',
    hourOfDay: 8,
    dayOfWeek: null,
    dayOfMonth: 1,
    reportingCurrency: null,
    lastFiredAt: null,
    lastStatus: null,
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
    recipients: [
      {
        id: 'r1', scheduleId: SCHEDULE_ID,
        email: 'finance@acme.example', displayName: 'Finance Director',
        isActive: true,
        unsubscribeToken: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        createdAt: '2026-08-01T00:00:00Z',
        updatedAt: '2026-08-01T00:00:00Z',
      },
    ],
  };
}

test.describe('Scheduled report delivery — happy path (Phase 17 §D.1)', () => {
  test('create → add recipient → view history → download → rerun', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: [
        'admin:manage_settings',
        'tenant.settings:manage_report_schedules',
        'tenant.settings:tenant_admin',
      ],
    });

    // Stateful catalogue + schedules list — mutations feed back into GETs.
    const catalogue = reportCatalogue();
    const schedules: ReturnType<typeof baseSchedule>[] = [];

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => catalogue);
    apiMocks.respond(`GET /tenants/${TENANT}/report-schedules`, () => schedules);

    apiMocks.respond(`POST /tenants/${TENANT}/report-schedules`, async (req) => {
      const body = JSON.parse(req.postData() ?? '{}');
      const created = {
        ...baseSchedule(),
        id: NEW_SCHEDULE_ID,
        reportKey: body.reportKey,
        reportLabel: body.reportKey === 'COMMISSION_STATEMENT' ? 'Commission statement' : body.reportKey,
        cadence: body.cadence,
        hourOfDay: body.hourOfDay,
        dayOfWeek: body.dayOfWeek ?? null,
        dayOfMonth: body.dayOfMonth ?? null,
        enabled: body.enabled,
        recipients: [],
      };
      schedules.push(created);
      const target = catalogue.find(r => r.reportKey === body.reportKey);
      if (target) target.activeScheduleCount = 1;
      return created;
    }, 201);

    apiMocks.respond(
      `POST /tenants/${TENANT}/report-schedules/:id/recipients`,
      async (req) => {
        const body = JSON.parse(req.postData() ?? '{}');
        const recipient = {
          id: `r-${schedules[0].recipients.length + 2}`,
          scheduleId: schedules[0].id,
          email: body.email,
          displayName: body.displayName ?? null,
          isActive: body.isActive ?? true,
          unsubscribeToken: 'ffffffff-1111-2222-3333-444444444444',
          createdAt: '2026-08-15T09:00:00Z',
          updatedAt: '2026-08-15T09:00:00Z',
        };
        schedules[0].recipients.push(recipient);
        return recipient;
      },
      201,
    );

    apiMocks.respond(`GET /reports/scheduled/schedules/:id/runs`, () => [
      {
        jobId: JOB_ID,
        scheduleId: SCHEDULE_ID,
        reportKey: 'COMMISSION_STATEMENT',
        status: 'completed',
        errorMessage: null,
        periodStart: '2026-07-01',
        periodEnd: '2026-07-31',
        requestedAt: '2026-08-01T08:00:00Z',
        completedAt: '2026-08-01T08:00:12Z',
        hasXlsx: true,
      },
    ]);

    apiMocks.respond(`GET /reports/scheduled/runs/:id/download`, () => 'xlsx-bytes');

    apiMocks.respond(`POST /reports/scheduled/:jobId/rerun`, () => ({
      jobId: JOB_ID, status: 'requested',
    }));

    await page.goto('/tenant/admin/settings/report-schedules');
    await expect(page.getByRole('heading', { name: 'Report schedules', exact: true })).toBeVisible();
    await expect(page.getByText(/No schedules configured yet/)).toBeVisible();

    // 1. Create a MONTHLY commission statement schedule.
    const commissionCand = page.locator('.candidate', { hasText: 'Commission statement' });
    await commissionCand.getByRole('button', { name: /^Create schedule$/ }).click();

    const createReq = page.waitForRequest(
      r => r.url().endsWith(`/api/v1/tenants/${TENANT}/report-schedules`) && r.method() === 'POST',
    );
    await commissionCand.getByRole('button', { name: /^Create$/ }).click();
    const posted = await createReq;
    expect(JSON.parse(posted.postData() ?? '{}')).toMatchObject({
      reportKey: 'COMMISSION_STATEMENT',
      cadence: 'MONTHLY',
      hourOfDay: 8,
      dayOfMonth: 1,
      enabled: true,
    });
    await expect(page.getByText('Commission statement').first()).toBeVisible();

    // 2. Add a second recipient.
    const card = page.locator('.schedule-card', { hasText: 'Commission statement' });
    // Recipient list is shown by default; add another via the inline form.
    await card.locator('input[name="email"]').fill('cfo@acme.example');
    await card.locator('input[name="displayName"]').fill('CFO');
    const addRecipReq = page.waitForRequest(
      r => r.url().includes(`/api/v1/tenants/${TENANT}/report-schedules/${NEW_SCHEDULE_ID}/recipients`)
           && r.method() === 'POST',
    );
    await card.getByRole('button', { name: /^Add recipient$/ }).click();
    const rBody = JSON.parse((await addRecipReq).postData() ?? '{}');
    expect(rBody.email).toBe('cfo@acme.example');

    // 3. Open Run history → GET /runs fires, one COMPLETED row shows.
    const runsReq = page.waitForRequest(
      r => r.url().includes(`/api/v1/reports/scheduled/schedules/${NEW_SCHEDULE_ID}/runs`)
           && r.method() === 'GET',
    );
    await card.getByRole('button', { name: /Run history/ }).click();
    await runsReq;
    await expect(card.getByText('completed')).toBeVisible();
    await expect(card.getByText('2026-07-01')).toBeVisible();

    // 4. Download the completed run.
    const downloadReq = page.waitForRequest(
      r => r.url().includes(`/api/v1/reports/scheduled/runs/${JOB_ID}/download`)
           && r.method() === 'GET',
    );
    await card.getByTitle('Download XLSX').click();
    await downloadReq;

    // 5. Re-run.
    const rerunReq = page.waitForRequest(
      r => r.url().includes(`/api/v1/reports/scheduled/${JOB_ID}/rerun`) && r.method() === 'POST',
    );
    await card.getByTitle('Re-run for the same period').click();
    await rerunReq;
    await expect(page.getByText(/Rerun queued/)).toBeVisible();
  });
});
