import type { Request } from '@playwright/test';
import { test, expect } from '../fixtures/test';
import { stubBillingAPIs, emptySeed, BillingGroup } from '../fixtures/billing-stubs';

test.describe('Billing — record transaction', () => {
  test('records a payment against a group', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view', 'billing:post_transactions'],
    });

    const seed = emptySeed();
    const acme: BillingGroup = {
      id: 'grp-e2e-acme',
      name: 'Acme Ltd',
      registrationNumber: 'REG-001',
      status: 'ACTIVE',
    };
    seed.groups.push(acme);
    stubBillingAPIs(apiMocks, seed);

    let postedTxn: Record<string, unknown> | null = null;
    apiMocks.respond('POST /transactions', async (req: Request) => {
      postedTxn = JSON.parse(req.postData() ?? '{}');
      return {
        id: 'txn-e2e-1',
        ...postedTxn,
        createdAt: new Date().toISOString(),
      };
    });

    await page.goto('/tenant/billing/transactions/add');
    await expect(page.getByRole('heading', { name: 'Record transaction' })).toBeVisible();

    // Target defaults to GROUP. Fill the input directly, then dispatch focus
    // + input events so Angular's onTargetQueryChange fires (which flips
    // showMatches on) and the ngModel value updates.
    const search = page.locator('input[name="targetQuery"]');
    await search.click();
    const searchResponse = page.waitForResponse(res =>
      res.url().includes('/groups/search') && res.status() === 200,
    );
    await search.pressSequentially('Acme', { delay: 80 });
    await searchResponse;
    // Give Angular a tick to project targetMatches into the DOM.
    await expect(page.locator('.suggestions')).toBeVisible({ timeout: 5000 });
    const suggestion = page.locator('.suggestions .suggestion', { hasText: 'Acme Ltd' }).first();
    await expect(suggestion).toBeVisible({ timeout: 5000 });
    // Transaction form's picker binds (click), not (mousedown) — charge-preview
    // and entity-picker use (mousedown) instead. Different templates, same idea.
    await suggestion.click();

    // Amount + reference are native inputs.
    await page.locator('input[name="amount"]').fill('120.50');
    // Currency defaults to USD (from tenant currency stub); no click needed.

    // Transaction type — pick Payment via app-select.
    await page.locator('app-select[name="transactionType"] .select-trigger').click();
    await page.locator('.select-option .option-label', { hasText: /^Payment/ }).first().click();

    await page.locator('input[name="reference"]').fill('E2E-REF-001');

    await page.getByRole('button', { name: /record transaction/i }).click();

    // The payload uses `transactionType` + `paymentMethod` (not the *Code names).
    await expect.poll(() => postedTxn, { timeout: 10_000 }).not.toBeNull();
    // Amount is coerced to number by Angular's [(ngModel)] on a type="number"
    // input, so 120.50 arrives as 120.5.
    expect(postedTxn).toMatchObject({
      amount: 120.5,
      currencyCode: 'USD',
      transactionType: 'PAYMENT',
      reference: 'E2E-REF-001',
      groupId: 'grp-e2e-acme',
    });
  });
});
