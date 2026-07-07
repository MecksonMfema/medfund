import { test, expect } from '../fixtures/test';
import { stubBillingAPIs, emptySeed, BillingGroup } from '../fixtures/billing-stubs';

test.describe('Billing — charge preview', () => {
  test('debounced typeahead picks a group and renders projected charge', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view', 'billing:view_creditors'],
    });

    const seed = emptySeed();
    const acme: BillingGroup = {
      id: 'grp-e2e-acme',
      name: 'Acme Ltd',
      registrationNumber: 'REG-001',
      status: 'ACTIVE',
    };
    seed.groups.push(acme);
    seed.chargePreview = {
      subjectType: 'GROUP',
      subjectId: acme.id,
      subjectName: acme.name,
      asOf: new Date().toISOString(),
      periodStart: '2026-08-01',
      periodEnd: '2026-08-31',
      excludedTerminating: 0,
      lines: [
        { personName: 'Ada Lovelace', personType: 'MEMBER', memberNumber: 'M000001',
          schemeName: 'Gold', amount: 100, currencyCode: 'USD' },
        { personName: 'Betty Lovelace', personType: 'DEPENDANT', memberNumber: 'M000001',
          schemeName: 'Gold', amount: 60, currencyCode: 'USD' },
      ],
      totals: { USD: 160 },
    };
    stubBillingAPIs(apiMocks, seed);

    await page.goto('/tenant/billing/charge-preview');
    await expect(page.getByRole('heading', { name: 'Charge preview' })).toBeVisible();

    // Group tab is the default; type into the search — 300ms debounce.
    const searchBox = page.locator('input[name="targetQuery"]');
    await searchBox.fill('Acme');
    // Wait for the suggestion to appear then click it.
    const suggestion = page.locator('.suggestions .suggestion', { hasText: 'Acme Ltd' }).first();
    await expect(suggestion).toBeVisible({ timeout: 5000 });
    await suggestion.click();

    await expect(page.locator('.result-title', { hasText: 'Acme Ltd' })).toBeVisible();
    await expect(page.locator('table.breakdown')).toBeVisible();
    await expect(page.locator('table.breakdown tbody tr')).toHaveCount(2);
    await expect(page.locator('table.breakdown tfoot tr .total')).toContainText('160');
  });
});
