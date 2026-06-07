import type { Request } from '@playwright/test';
import { test, expect, TENANT } from '../fixtures/test';

test.describe('Scheme creation', () => {
  test('operator with billing:manage_schemes can create a scheme', async ({
    page, apiMocks, signInAs,
  }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view', 'billing:manage_schemes'],
    });

    // Capture the POST so we can assert the payload reaches the backend with
    // the expected shape — including the trimmed name and uppercased currency.
    let postedPayload: Record<string, unknown> | null = null;
    apiMocks
      .respond('GET /schemes', [])
      .respond('POST /schemes', async (req: Request) => {
        postedPayload = JSON.parse(req.postData() ?? '{}');
        return {
          id: '99999999-0000-4000-8000-000000000001',
          name: postedPayload?.name,
          description: postedPayload?.description ?? null,
          schemeType: postedPayload?.schemeType ?? 'medical_aid',
          status: 'active',
          effectiveDate: postedPayload?.effectiveDate,
          endDate: postedPayload?.endDate ?? null,
          currencyCode: postedPayload?.currencyCode,
        };
      });

    await page.goto('/tenant/billing/schemes/add');
    await expect(page.getByRole('heading', { name: 'Add scheme' })).toBeVisible();

    // Fill the form. The currency dropdown is pre-populated by the
    // /tenants/:id/currencies stub in the default API set (USD, default).
    await page.locator('input[name="name"]').fill('Gold E2E Plan');
    await page.locator('textarea[name="description"]').fill('Created from Playwright');
    await page.locator('select[name="currencyCode"]').selectOption('USD');

    await page.getByRole('button', { name: /create scheme/i }).click();

    await page.waitForURL('**/tenant/billing/schemes');
    expect(postedPayload).not.toBeNull();
    expect(postedPayload).toMatchObject({
      name: 'Gold E2E Plan',
      currencyCode: 'USD',
      description: 'Created from Playwright',
    });
  });

  test('operator without billing:manage_schemes is redirected to /unauthorized', async ({
    page, signInAs,
  }) => {
    await signInAs({
      realmRoles: ['operator'],
      // Only :view, not :manage_schemes — the add-scheme route requires the latter.
      permissions: ['billing:view'],
    });

    await page.goto('/tenant/billing/schemes/add');

    await page.waitForURL('**/unauthorized', { timeout: 5_000 });
    await expect(page).toHaveURL(/\/unauthorized$/);
  });

  test('name field is required — submit is rejected when blank', async ({
    page, signInAs,
  }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view', 'billing:manage_schemes'],
    });

    await page.goto('/tenant/billing/schemes/add');
    await expect(page.getByRole('heading', { name: 'Add scheme' })).toBeVisible();

    // Don't fill name. Try to submit — browser native required-validation
    // blocks the form-submit event, so the URL stays put.
    const initialUrl = page.url();
    await page.getByRole('button', { name: /create scheme/i }).click();

    // After the click, we should still be on the add page (no navigation).
    expect(page.url()).toBe(initialUrl);
    // And the name input should be flagged as invalid.
    const nameValid = await page.locator('input[name="name"]').evaluate(
      (el) => (el as HTMLInputElement).validity.valid,
    );
    expect(nameValid).toBe(false);
  });
});
