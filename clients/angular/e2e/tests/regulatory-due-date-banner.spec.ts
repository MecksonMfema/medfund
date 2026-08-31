import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 27 REG14 e2e coverage for the reports-hub due-date banner
 * (Phase 8 UI + Phase 0 REG14 backend). One GET returns every applicable
 * Phase-16 regulator report; the hub renders a banner per report card.
 *
 * <p>All computation is server-side — the component only styles the row
 * and formats the copy ("Due in 4 days" / "Due today" / "Overdue by 3
 * days" / "Filed").
 */

test.describe('Regulatory due-date banner (Phase 27 REG14)', () => {
  test('reports-hub renders a banner per matching report card + colour-codes by severity',
      async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['finance:view', 'finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, [
      { id: null, tenantId: TENANT, reportKey: 'IPEC_QUARTERLY_RETURN',
        label: 'IPEC — quarterly return (ZW)', family: 'REGULATORY',
        familyLabel: 'Regulatory', enabled: true, cadenced: true,
        updatedAt: null, updatedBy: null },
      { id: null, tenantId: TENANT, reportKey: 'VAT_RETURN',
        label: 'VAT return', family: 'REGULATORY',
        familyLabel: 'Regulatory', enabled: true, cadenced: true,
        updatedAt: null, updatedBy: null },
      { id: null, tenantId: TENANT, reportKey: 'AML_STR',
        label: 'AML / STR periodic summary', family: 'REGULATORY',
        familyLabel: 'Regulatory', enabled: true, cadenced: true,
        updatedAt: null, updatedBy: null },
    ]);

    apiMocks.respond('GET /reports/regulatory/due-dates', [
      // Upcoming — amber
      { reportKey: 'IPEC_QUARTERLY_RETURN',
        reportLabel: 'IPEC — quarterly return (ZW)',
        cadence: 'QUARTERLY',
        periodStart: '2026-07-01', periodEnd: '2026-09-30',
        dueDate: '2026-10-31',
        daysUntilDue: 4,
        submissionStatus: 'PENDING',
        severity: 'AMBER' },
      // Overdue — red
      { reportKey: 'VAT_RETURN',
        reportLabel: 'VAT return',
        cadence: 'MONTHLY',
        periodStart: '2026-06-01', periodEnd: '2026-06-30',
        dueDate: '2026-07-25',
        daysUntilDue: -3,
        submissionStatus: 'PENDING',
        severity: 'RED' },
      // Filed — info
      { reportKey: 'AML_STR',
        reportLabel: 'AML / STR periodic summary',
        cadence: 'QUARTERLY',
        periodStart: '2026-04-01', periodEnd: '2026-06-30',
        dueDate: '2026-07-31',
        daysUntilDue: -30,
        submissionStatus: 'SUBMITTED',
        severity: 'INFO' },
    ]);

    await page.goto('/tenant/finance/reports');
    await expect(page.getByRole('heading', { name: /^Reports$/, exact: true })).toBeVisible();

    // Three banners, one per report card that has a matching due-date row.
    const banners = page.locator('[data-testid="due-date-banner"]');
    await expect(banners).toHaveCount(3);

    // Amber "Due in 4 days" for IPEC
    await expect(page.locator('.banner-amber', { hasText: 'Due in 4 days' })).toBeVisible();
    // Red "Overdue by 3 days" for VAT
    await expect(page.locator('.banner-red', { hasText: 'Overdue by 3 days' })).toBeVisible();
    // Info "Filed" for AML
    await expect(page.locator('.banner-info', { hasText: 'Filed' })).toBeVisible();
  });

  test('hub renders without banners when the due-dates endpoint returns an empty list',
      async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['finance:view'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, [
      { id: null, tenantId: TENANT, reportKey: 'IPEC_QUARTERLY_RETURN',
        label: 'IPEC — quarterly return (ZW)', family: 'REGULATORY',
        familyLabel: 'Regulatory', enabled: true, cadenced: true,
        updatedAt: null, updatedBy: null },
    ]);
    apiMocks.respond('GET /reports/regulatory/due-dates', []);

    await page.goto('/tenant/finance/reports');
    await expect(page.getByRole('heading', { name: /^Reports$/, exact: true })).toBeVisible();
    await expect(page.getByText('IPEC — quarterly return (ZW)')).toBeVisible();
    // No matching banner row → nothing renders.
    await expect(page.locator('[data-testid="due-date-banner"]')).toHaveCount(0);
  });
});
