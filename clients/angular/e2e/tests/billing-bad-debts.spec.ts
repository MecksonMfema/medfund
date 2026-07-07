import { test, expect } from '../fixtures/test';
import { stubBillingAPIs, emptySeed } from '../fixtures/billing-stubs';

test.describe('Billing — bad debts', () => {
  test('renders bad debts list per currency', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view', 'billing:manage_bad_debts'],
    });

    const seed = emptySeed();
    seed.badDebts.push(
      {
        subjectType: 'INDIVIDUAL',
        subjectId: 'mem-1',
        subjectName: 'Bob Zombie',
        currencyCode: 'USD',
        outstandingBalance: 750,
        daysSinceLastActivity: 210,
      },
      {
        subjectType: 'GROUP',
        subjectId: 'grp-1',
        subjectName: 'Closed Co',
        currencyCode: 'USD',
        outstandingBalance: 4200,
        daysSinceLastActivity: 180,
      },
    );
    stubBillingAPIs(apiMocks, seed);

    await page.goto('/tenant/billing/bad-debts');
    await expect(page.getByRole('heading', { name: 'Bad debts' })).toBeVisible();

    // The tenant currencies stub returns USD as the default, so bad-debts
    // auto-selects it on init and fires the first fetchPage(); no manual
    // currency click is needed. The seeded rows should surface.
    await expect(page.locator('tr', { hasText: 'Bob Zombie' })).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('tr', { hasText: 'Closed Co' })).toBeVisible();
  });
});
