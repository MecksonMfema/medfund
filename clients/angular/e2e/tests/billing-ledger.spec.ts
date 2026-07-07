import { test, expect } from '../fixtures/test';
import { stubBillingAPIs, emptySeed, BillingGroup } from '../fixtures/billing-stubs';

test.describe('Billing — ledger', () => {
  test('search a group ledger over a period and render the summary + rows', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view', 'billing:view_statements'],
    });

    const seed = emptySeed();
    const acme: BillingGroup = {
      id: 'grp-e2e-acme',
      name: 'Acme Ltd',
      registrationNumber: 'REG-001',
      status: 'ACTIVE',
    };
    seed.groups.push(acme);
    seed.statement = {
      header: {
        targetType: 'GROUP',
        targetId: acme.id,
        targetName: acme.name,
        targetCode: acme.registrationNumber,
        periodStart: '2026-08-01',
        periodEnd: '2026-08-31',
        currencyCode: 'USD',
        // Ledger template consumes these as StatementHeader currency fields.
        openingBalance: '0.00',
        totalCharges: '250.00',
        totalPayments: '100.00',
        closingBalance: '150.00',
      } as any,
      lines: [
        { date: '2026-08-01', description: 'Balance brought forward', runningBalance: 0,   type: 'OPENING_BALANCE' } as any,
        { date: '2026-08-05', description: 'August contribution',      runningBalance: 250, debit: 250, type: 'CONTRIBUTION' } as any,
        { date: '2026-08-15', description: 'Payment',                  runningBalance: 150, credit: 100, type: 'TRANSACTION' } as any,
        { date: '2026-08-31', description: 'Balance carried forward',  runningBalance: 150, type: 'CLOSING_BALANCE' } as any,
      ],
    };
    stubBillingAPIs(apiMocks, seed);

    await page.goto('/tenant/billing/ledger');
    await expect(page.getByRole('heading', { name: 'Ledger' })).toBeVisible();

    // Group is the default tab. Typeahead → pick Acme.
    const search = page.locator('input[name="targetQuery"]');
    await search.focus();
    const respPromise = page.waitForResponse(r => r.url().includes('/groups/search') && r.status() === 200);
    await search.pressSequentially('Acme', { delay: 60 });
    await respPromise;
    await expect(page.locator('.suggestions .suggestion', { hasText: 'Acme Ltd' })).toBeVisible({ timeout: 10_000 });
    await page.locator('.suggestions .suggestion', { hasText: 'Acme Ltd' }).first().dispatchEvent('mousedown');

    // Period.
    await page.locator('input[name="periodStart"]').fill('2026-08-01');
    await page.locator('input[name="periodEnd"]').fill('2026-08-31');

    await page.getByRole('button', { name: /^Search$/ }).click();

    // Summary cards + rows render off the stubbed statement.
    await expect(page.locator('.summary-card', { hasText: 'Opening balance' })).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('.summary-card.highlight', { hasText: 'Closing balance' })).toContainText('150');
    await expect(page.locator('table.ledger-table tbody tr')).toHaveCount(4);
  });
});
