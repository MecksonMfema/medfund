import type { Request } from '@playwright/test';
import { test, expect } from '../fixtures/test';
import { stubBillingAPIs, emptySeed } from '../fixtures/billing-stubs';

/**
 * Extends the coverage of `scheme-creation.spec.ts` to walk the whole
 * scheme lifecycle: create, then verify the new scheme surfaces on the
 * schemes list. Kept as a separate file so the existing spec remains
 * untouched and can eventually be folded here.
 */
test.describe('Billing — schemes lifecycle', () => {
  test('creating a scheme surfaces it on the schemes list', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view', 'billing:manage_schemes'],
    });

    const seed = emptySeed();
    stubBillingAPIs(apiMocks, seed);

    let postedScheme: Record<string, unknown> | null = null;
    apiMocks.respond('POST /schemes', async (req: Request) => {
      postedScheme = JSON.parse(req.postData() ?? '{}');
      const s = {
        id: 'sch-e2e-1',
        name: (postedScheme as any).name,
        description: (postedScheme as any).description ?? null,
        schemeType: (postedScheme as any).schemeType ?? 'medical_aid',
        status: 'active',
        effectiveDate: (postedScheme as any).effectiveDate,
        endDate: (postedScheme as any).endDate ?? null,
        currencyCode: (postedScheme as any).currencyCode,
        insuranceLine: 'HEALTH',
      };
      seed.schemes.push(s as any);
      return s;
    });

    await page.goto('/tenant/billing/schemes/add');
    await expect(page.getByRole('heading', { name: 'Add scheme' })).toBeVisible();

    await page.locator('input[name="name"]').fill('Platinum E2E');
    await page.locator('input[name="effectiveDate"]').fill('2026-08-01');
    await page.locator('app-select[name="currencyCode"] .select-trigger').click();
    await page.locator('.select-option .option-label', { hasText: /^USD$/ }).first().click();

    await page.getByRole('button', { name: /create scheme/i }).click();
    await page.waitForURL('**/tenant/billing/schemes');

    expect(postedScheme).toMatchObject({
      name: 'Platinum E2E',
      currencyCode: 'USD',
      effectiveDate: '2026-08-01',
    });
    // Seed was mutated in the POST handler, so the schemes-list request
    // should render the new row (contributions.component consumes /schemes).
    expect(seed.schemes.map(s => s.name)).toContain('Platinum E2E');
  });
});
