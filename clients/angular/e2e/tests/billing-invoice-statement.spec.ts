import { test, expect } from '../fixtures/test';
import { stubBillingAPIs, emptySeed, BillingInvoice } from '../fixtures/billing-stubs';

test.describe('Billing — invoices list + statement', () => {
  test('opens statement from list row', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view'],
    });

    const seed = emptySeed();
    const inv: BillingInvoice = {
      id: 'inv-e2e-1',
      invoiceNumber: 'INV-2026-08-0001',
      status: 'ISSUED',
      holderType: 'GROUP',
      holderId: 'grp-e2e-acme',
      holderName: 'Acme Ltd',
      targetCode: 'REG-001',
      periodStart: '2026-08-01',
      periodEnd: '2026-08-31',
      currencyCode: 'USD',
      insuranceLine: 'HEALTH',
      totalAmount: 250,
      amountDue: 250,
      committedAt: new Date().toISOString(),
    };
    seed.invoices.push(inv);
    seed.statement = {
      header: {
        targetType: 'GROUP',
        targetId: inv.holderId,
        targetName: inv.holderName,
        targetCode: inv.targetCode,
        periodStart: inv.periodStart,
        periodEnd: inv.periodEnd,
        currencyCode: inv.currencyCode,
      },
      lines: [
        { date: inv.periodStart, description: 'Balance brought forward', runningBalance: 0, type: 'OPENING_BALANCE' },
        { date: inv.periodEnd,   description: 'Amount Due',              runningBalance: 250, type: 'CLOSING_BALANCE' },
      ],
    };
    stubBillingAPIs(apiMocks, seed);

    await page.goto('/tenant/billing/view');
    await expect(page.getByRole('heading', { name: 'Contribution statements' })).toBeVisible();
    // Row is a <tr> inside <app-data-table>. Click by invoice-number text.
    const row = page.locator('tr', { hasText: 'INV-2026-08-0001' }).first();
    await row.click();
    await page.waitForURL('**/tenant/billing/view/inv-e2e-1');

    await expect(page.getByRole('heading', { name: 'INV-2026-08-0001' })).toBeVisible();
    await expect(page.locator('.holder')).toContainText('Acme Ltd');
    await expect(page.locator('table.ledger')).toBeVisible();
    // Closing balance row shows amount due.
    await expect(page.locator('table.ledger tr.row-due')).toContainText('250');
  });
});
