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

function stubDashboardAPIs(apiMocks: ApiMocks): void {
  apiMocks
    .respond('GET /tenant-stats', emptyStats)
    .respond('GET /tenant-stats/charts', emptyCharts)
    .respond('GET /tenant-stats/claims-status-distribution', { total: 0, buckets: [] })
    .respond('GET /tenant-stats/recent-claims', [])
    .respond('GET /tenant-stats/adjudicator-workload', { unassigned: 0, adjudicators: [] })
    .respond('GET /tenant-stats/recent-contributions', [])
    .respond('GET /tenant-stats/payments-status-distribution', { total: 0, buckets: [] })
    .respond('GET /tenant-stats/recent-payments', [])
    .respond('GET /tenant-stats/top-payees', [])
    .respond('GET /tenant-stats/payment-method-distribution', { total: 0, buckets: [] });
}

test.describe('Super-admin permission-gated navigation', () => {
  test('super-admin dropdown exposes Tenant Admin + Platform Admin hops on the operational dashboard', async ({
    page, apiMocks, signInAs,
  }) => {
    await signInAs({
      realmRoles: ['super_admin'],
      permissions: [],
    });
    stubDashboardAPIs(apiMocks);

    await page.goto('/tenant/dashboard');

    await page.locator('.t-user').click();

    const menu = page.locator('.t-user-menu');
    await expect(menu).toBeVisible();
    // On the operational portal, sidebarVariant === 'operational' — the
    // "Operational Portal" self-hop is hidden. Remaining hops are
    // "Tenant Admin" (canAccessAdmin → true for super admin) and "Platform Admin".
    await expect(menu.getByRole('button', { name: /tenant admin/i })).toBeVisible();
    await expect(menu.getByRole('button', { name: /platform admin/i })).toBeVisible();
    await expect(menu.getByRole('button', { name: /sign out/i })).toBeVisible();
  });

  test('clicking Tenant Admin from the menu navigates to the IT-admin dashboard', async ({
    page, apiMocks, signInAs,
  }) => {
    await signInAs({
      realmRoles: ['super_admin'],
      permissions: [],
    });
    stubDashboardAPIs(apiMocks);
    // The tenant-admin dashboard hits a different stats set; stub just enough
    // for the shell to land without unstubbed 404s.
    apiMocks
      .respond('GET /audit*', { events: [], totalCount: 0, page: 1, pageSize: 50 })
      .respond(`GET /tenants/${TENANT}/users*`, { users: [], page: 1, pageSize: 50, totalCount: 0 });

    await page.goto('/tenant/dashboard');
    await page.locator('.t-user').click();
    await page.locator('.t-user-menu').getByRole('button', { name: /tenant admin/i }).click();

    await page.waitForURL('**/tenant/admin/dashboard');
    expect(page.url()).toContain('/tenant/admin/dashboard');
  });

  test('non-super-admin operator does NOT see admin or Platform hops in the dropdown', async ({
    page, apiMocks, signInAs,
  }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view'],
    });
    stubDashboardAPIs(apiMocks);

    await page.goto('/tenant/dashboard');
    await page.locator('.t-user').click();

    const menu = page.locator('.t-user-menu');
    await expect(menu).toBeVisible();
    // canAccessAdmin is false (not super_admin, not tenant_admin) → no admin hop.
    await expect(menu.getByRole('button', { name: /tenant admin/i })).toHaveCount(0);
    // Platform Admin is super-admin-only.
    await expect(menu.getByRole('button', { name: /platform admin/i })).toHaveCount(0);
    // The sign-out button stays for everyone.
    await expect(menu.getByRole('button', { name: /sign out/i })).toBeVisible();
  });
});
