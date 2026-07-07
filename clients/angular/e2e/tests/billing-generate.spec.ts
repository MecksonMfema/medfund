import { test, expect } from '../fixtures/test';
import { stubBillingAPIs, emptySeed, BillingInvoice } from '../fixtures/billing-stubs';

test.describe('Billing — generate wizard', () => {
  test('preview → commit → land on committed screen; downstream invoices list', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view', 'billing:generate_billing'],
    });

    const seed = emptySeed();
    seed.billingPreview = {
      totalRows: 3,
      groupInvoicesProjected: 1,
      individualInvoicesProjected: 0,
      totalsByCurrency: { USD: 250 } as any,
      sample: [
        { routingKey: 'acme|USD', holderName: 'Acme Ltd', holderType: 'GROUP', currencyCode: 'USD',
          personName: 'Ada Lovelace', personType: 'MEMBER', memberNumber: 'M000001',
          schemeName: 'Gold', ageBand: '18-64', amount: 100 } as any,
      ],
      cooldownActive: false,
      cooldownRemainingMinutes: 0,
      membershipModel: 'BOTH',
    };
    stubBillingAPIs(apiMocks, seed);

    await page.goto('/tenant/billing/generate');
    await expect(page.getByRole('heading', { name: 'Generate billing' })).toBeVisible();

    // Pick next month.
    const now = new Date();
    const next = new Date(now.getFullYear(), now.getMonth() + 1, 1);
    const yyyyMm = `${next.getFullYear()}-${String(next.getMonth() + 1).padStart(2, '0')}`;
    await page.locator('input[name="billingMonth"]').fill(yyyyMm);
    await page.getByRole('button', { name: /^Preview/ }).click();

    // Preview step renders a "Preview · <month>" card title.
    await expect(page.locator('.form-card__title', { hasText: /^Preview ·/ })).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.stat', { hasText: 'Contribution rows' })).toContainText('3');

    // Commit — post-commit the screen switches to the "committed" step with
    // "View contributions" as the CTA.
    await page.getByRole('button', { name: /^Commit/ }).click();

    // Seed a matching invoice for the downstream list.
    const inv: BillingInvoice = {
      id: 'inv-e2e-1',
      invoiceNumber: 'INV-2026-08-0001',
      status: 'ISSUED',
      holderType: 'GROUP',
      holderId: 'grp-e2e-acme',
      holderName: 'Acme Ltd',
      periodStart: `${yyyyMm}-01`,
      periodEnd: `${yyyyMm}-28`,
      currencyCode: 'USD',
      insuranceLine: 'HEALTH',
      totalAmount: 250,
      amountDue: 250,
      committedAt: new Date().toISOString(),
    };
    seed.invoices.push(inv);

    await expect(page.getByRole('heading', { name: /Billing committed/i })).toBeVisible({ timeout: 10_000 });
    // The success card renders a "View contributions" CTA in its footer; the
    // header link with the same text lives outside the card, so scope to
    // the success-card's footer to avoid a strict-mode collision.
    await page.locator('.success-card footer').getByRole('link', { name: /View contributions/i }).click();
    await page.waitForURL('**/tenant/billing/view');
    await expect(page.getByRole('heading', { name: 'Contribution statements' })).toBeVisible();
  });
});
