import { test, expect, TENANT } from '../fixtures/test';
import { ApiMocks } from '../fixtures/api-mocks';

const emptyStats = {
  totalStaff: 0, activeStaff: 0, suspendedStaff: 0, pendingStaff: 0,
  totalMembers: 0, activeMembers: 0, enrolledMembers: 0,
  newMembersThisMonth: 0, newGroupsThisMonth: 0,
  claimsNewTasks: 0, claimsThisMonth: 0,
  claimsAcceptedThisMonth: 0, claimsRejectedThisMonth: 0,
  schemesActive: 0, contributionsPending: 0,
  contributionsAmountThisMonth: 0, contributionsAmountThisYear: 0,
  paymentsPending: 0, paymentsAmountThisMonth: 0, paymentsAmountThisYear: 0,
  paymentsAdvanceCount: 0, paymentsAdvanceAmount: 0, paymentsFailedCount: 0,
  claimsShortfallAmount: 0,
  contributionsAmountThisMonthByCurrency: {},
  contributionsAmountThisYearByCurrency:  {},
  paymentsAmountThisMonthByCurrency:      {},
  paymentsAmountThisYearByCurrency:       {},
};

const emptyCharts = {
  claimsByMonth: [], contributionsAmountByMonth: [], paymentsAmountByMonth: [],
  claimsByMonthByCurrency: {},
  contributionsAmountByMonthByCurrency: {},
  paymentsAmountByMonthByCurrency: {},
};

const emptyDistribution = { total: 0, buckets: [] };
const emptyWorkload = { unassigned: 0, adjudicators: [] };

function stubDashboardAPIs(apiMocks: ApiMocks): void {
  apiMocks
    .respond('GET /tenant-stats', emptyStats)
    .respond('GET /tenant-stats/charts', emptyCharts)
    .respond('GET /tenant-stats/claims-status-distribution', emptyDistribution)
    .respond('GET /tenant-stats/recent-claims', [])
    .respond('GET /tenant-stats/adjudicator-workload', emptyWorkload)
    .respond('GET /tenant-stats/recent-contributions', [])
    .respond('GET /tenant-stats/payments-status-distribution', { total: 0, buckets: [] })
    .respond('GET /tenant-stats/recent-payments', [])
    .respond('GET /tenant-stats/top-payees', [])
    .respond('GET /tenant-stats/payment-method-distribution', { total: 0, buckets: [] });
}

test.describe('Dashboard tab permission gating', () => {
  test('user with only billing:view sees Billing — not Claims or Finance', async ({
    page, apiMocks, signInAs,
  }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view'],
    });
    stubDashboardAPIs(apiMocks);

    await page.goto('/tenant/dashboard');

    const tabStrip = page.locator('[role="tablist"].tab-strip');
    await expect(tabStrip).toBeVisible();

    await expect(tabStrip.getByRole('tab', { name: 'Billing' })).toBeVisible();
    await expect(tabStrip.getByRole('tab', { name: 'Billing' })).toHaveAttribute('aria-selected', 'true');
    await expect(tabStrip.getByRole('tab', { name: 'Claims' })).toHaveCount(0);
    await expect(tabStrip.getByRole('tab', { name: 'Finance' })).toHaveCount(0);
  });

  test('user with claims+finance sees both tabs in display order — Claims first', async ({
    page, apiMocks, signInAs,
  }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['claims:view', 'finance:view'],
    });
    stubDashboardAPIs(apiMocks);

    await page.goto('/tenant/dashboard');
    const tabStrip = page.locator('[role="tablist"].tab-strip');

    await expect(tabStrip.getByRole('tab', { name: 'Claims' })).toBeVisible();
    await expect(tabStrip.getByRole('tab', { name: 'Finance' })).toBeVisible();
    await expect(tabStrip.getByRole('tab', { name: 'Billing' })).toHaveCount(0);
    // Claims is listed second in the tabs array — but with Billing absent it
    // is the first allowed, so it auto-selects.
    await expect(tabStrip.getByRole('tab', { name: 'Claims' })).toHaveAttribute('aria-selected', 'true');
  });

  test('super admin sees every tab even with empty permission set', async ({
    page, apiMocks, signInAs,
  }) => {
    await signInAs({
      realmRoles: ['super_admin'],
      permissions: [], // server-side super admin has no tenant-scoped perms
    });
    stubDashboardAPIs(apiMocks);

    await page.goto('/tenant/dashboard');
    const tabStrip = page.locator('[role="tablist"].tab-strip');

    await expect(tabStrip.getByRole('tab', { name: 'Billing' })).toBeVisible();
    await expect(tabStrip.getByRole('tab', { name: 'Claims' })).toBeVisible();
    await expect(tabStrip.getByRole('tab', { name: 'Finance' })).toBeVisible();
    // Display order — Billing is first in the tabs array, super admin auto-selects it.
    await expect(tabStrip.getByRole('tab', { name: 'Billing' })).toHaveAttribute('aria-selected', 'true');
  });

  test('user with zero view permissions sees no tab strip at all', async ({
    page, apiMocks, signInAs,
  }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['members:view'], // domain that has no tab on this dashboard
    });
    stubDashboardAPIs(apiMocks);

    await page.goto('/tenant/dashboard');

    await expect(page.locator('[role="tablist"].tab-strip')).toHaveCount(0);
  });

  test('clicking a tab updates the aria-selected state', async ({
    page, apiMocks, signInAs,
  }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view', 'claims:view', 'finance:view'],
    });
    stubDashboardAPIs(apiMocks);

    await page.goto('/tenant/dashboard');
    const tabStrip = page.locator('[role="tablist"].tab-strip');

    // Initial auto-selection is Billing (first in display order).
    await expect(tabStrip.getByRole('tab', { name: 'Billing' })).toHaveAttribute('aria-selected', 'true');

    await tabStrip.getByRole('tab', { name: 'Finance' }).click();

    await expect(tabStrip.getByRole('tab', { name: 'Finance' })).toHaveAttribute('aria-selected', 'true');
    await expect(tabStrip.getByRole('tab', { name: 'Billing' })).toHaveAttribute('aria-selected', 'false');
  });
});
