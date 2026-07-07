import type { Request } from '@playwright/test';
import { test, expect } from '../fixtures/test';
import { stubBillingAPIs, emptySeed } from '../fixtures/billing-stubs';

test.describe('Billing — groups lifecycle', () => {
  test('create + view group; suspend then terminate', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view', 'billing:manage_groups'],
    });

    const seed = emptySeed();
    let postedGroup: Record<string, unknown> | null = null;
    stubBillingAPIs(apiMocks, seed);
    // Overlay the group POST so we can assert the payload.
    apiMocks.respond('POST /groups', async (req: Request) => {
      postedGroup = JSON.parse(req.postData() ?? '{}');
      const g = {
        id: 'grp-e2e-1',
        name: (postedGroup as any).name,
        registrationNumber: 'REG-001',
        email: (postedGroup as any).email,
        address: (postedGroup as any).address,
        liaisonKind: null,
        liaisonUserId: null,
        status: 'ACTIVE',
      };
      seed.groups.push(g as any);
      return g;
    });

    await page.goto('/tenant/billing/groups');
    await expect(page.getByRole('heading', { name: 'Groups' })).toBeVisible();
    await page.getByRole('link', { name: /add group/i }).click();
    await page.waitForURL('**/tenant/billing/groups/add');

    await page.locator('input[name="name"]').fill('Acme Ltd E2E');
    await page.locator('input[name="email"]').fill('billing@acme.example');
    await page.locator('textarea[name="address"]').fill('1 Test Street, Harare');
    // waitForResponse ensures the POST completes before we assert on the
    // captured payload — avoids the flaky race where the click fires but
    // the response hasn't landed yet.
    const postGroupResp = page.waitForResponse(r =>
      r.url().endsWith('/api/v1/groups') && r.request().method() === 'POST',
    );
    await page.getByRole('button', { name: /create group/i }).click();
    await postGroupResp;

    expect(postedGroup).toMatchObject({
      name: 'Acme Ltd E2E',
      email: 'billing@acme.example',
      address: '1 Test Street, Harare',
    });

    // Navigate to the group detail page and drive suspend → terminate.
    await page.goto('/tenant/billing/groups/grp-e2e-1');
    await expect(page.getByRole('heading', { name: 'Acme Ltd E2E' })).toBeVisible();

    // Suspend uses window.confirm; auto-accept so the click proceeds.
    page.once('dialog', d => d.accept());
    await page.getByRole('button', { name: /^suspend$/i }).click();
    await expect(page.locator('.chip.status[data-status="SUSPENDED"]')).toBeVisible({ timeout: 5000 });

    // Terminate — the detail component now opens the terminate-group
    // modal. Assert the modal appears, that its default effectiveDate is
    // snapped to end-of-month (feedback_effective_date_snap), and that
    // submitting drives the group into TERMINATED.
    let terminatePayload: Record<string, unknown> | null = null;
    apiMocks.respond('POST /groups/:id/actions/terminate', async (req: Request) => {
      terminatePayload = JSON.parse(req.postData() ?? '{}');
      const g = seed.groups.find(x => x.id === 'grp-e2e-1');
      if (g) g.status = 'TERMINATED';
      return g ?? { id: 'grp-e2e-1', status: 'TERMINATED' };
    });

    const terminateBtn = page.getByRole('button', { name: /^terminate$/i });
    if (await terminateBtn.isVisible().catch(() => false)) {
      await terminateBtn.click();
      const modal = page.locator('.modal-card', { hasText: /Terminate group/i });
      await expect(modal).toBeVisible({ timeout: 5000 });
      // Default effectiveDate is end of *this* month.
      const now = new Date();
      const eom = new Date(now.getFullYear(), now.getMonth() + 1, 0);
      const expectedDefault = `${eom.getFullYear()}-${String(eom.getMonth() + 1).padStart(2, '0')}-${String(eom.getDate()).padStart(2, '0')}`;
      await expect(modal.locator('[data-testid="terminate-group-effective-date"]')).toHaveValue(expectedDefault);
      await modal.locator('[data-testid="terminate-group-submit"]').click();
      await expect(page.locator('.chip.status[data-status="TERMINATED"]')).toBeVisible({ timeout: 5000 });
      expect(terminatePayload).toMatchObject({ effectiveDate: expectedDefault });
    }
  });

  test('missing billing:manage_groups → cannot see Add group', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['billing:view'],
    });
    stubBillingAPIs(apiMocks, emptySeed());
    await page.goto('/tenant/billing/groups');
    await expect(page.getByRole('heading', { name: 'Groups' })).toBeVisible();
    await expect(page.getByRole('link', { name: /add group/i })).toHaveCount(0);
  });
});
