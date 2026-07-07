import type { Request } from '@playwright/test';
import { test, expect } from '../fixtures/test';
import { stubBillingAPIs, emptySeed, BillingMember, BillingDependant } from '../fixtures/billing-stubs';

/**
 * Add a dependant with a back-dated enrolment date (V046/V047 —
 * arrears path per memory `project_backdated_enrolment_adjustment`),
 * then deactivate that dependant with an explicit effective date and
 * assert the stub receives both correctly.
 */
test.describe('Member operations — dependant lifecycle', () => {
  test('add dependant with enrollmentDate, then deactivate with effectiveDate', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['members:view', 'members:update', 'billing:manage_members'],
    });

    const seed = emptySeed();
    const member: BillingMember = {
      id: 'mem-e2e-1',
      memberNumber: 'M000001',
      firstName: 'Ada', lastName: 'Lovelace',
      dateOfBirth: '1990-01-01',
      email: 'ada@example.com', phone: '',
      status: 'ACTIVE',
      groupId: null, schemeId: null,
      enrollmentDate: '2025-01-01',
      createdAt: '2025-01-01T00:00:00Z',
    };
    seed.members.push(member);
    stubBillingAPIs(apiMocks, seed);

    let postedDep: Record<string, unknown> | null = null;
    apiMocks.respond('POST /dependants', async (req: Request) => {
      postedDep = JSON.parse(req.postData() ?? '{}');
      const d: BillingDependant = {
        id: 'dep-e2e-1',
        memberId: member.id,
        firstName: (postedDep as any).firstName,
        lastName: (postedDep as any).lastName,
        dateOfBirth: (postedDep as any).dateOfBirth,
        gender: (postedDep as any).gender,
        relationship: (postedDep as any).relationship,
        status: 'active',
        enrollmentDate: (postedDep as any).enrollmentDate,
      };
      seed.dependants.push(d);
      return d;
    });

    let deactivateBody: Record<string, unknown> | null = null;
    apiMocks.respond('POST /dependants/:id/deactivate', async (req: Request) => {
      deactivateBody = JSON.parse(req.postData() ?? '{}');
      const d = seed.dependants.find(x => x.id === 'dep-e2e-1');
      if (d) d.status = 'deactivated';
      return d ?? { id: 'dep-e2e-1', status: 'deactivated' };
    });

    await page.goto(`/tenant/members/${member.id}`);
    await expect(page.getByRole('heading', { name: /Ada/ })).toBeVisible({ timeout: 10_000 });

    // "Add dependant" button opens the inline collapsible form.
    await page.getByRole('button', { name: /^Add dependant$/ }).click();

    await page.locator('input[name="dependantFirstName"]').fill('Betty');
    await page.locator('input[name="dependantLastName"]').fill('Lovelace');
    await page.locator('input[name="dependantRelationship"]').fill('Daughter');
    await page.locator('input[name="dependantDob"]').fill('2015-05-01');
    await page.locator('input[name="dependantNationalId"]').fill('ID-KID-1');
    // Gender is an app-select — open + pick first option.
    await page.locator('app-select[name="dependantGender"] .select-trigger').click();
    await page.locator('.select-option .option-label').first().click();
    // Back-dated enrolment (2 months ago, first-of-month) — arrears path.
    const now = new Date();
    const back = new Date(now.getFullYear(), now.getMonth() - 2, 1);
    const backDate = `${back.getFullYear()}-${String(back.getMonth() + 1).padStart(2, '0')}-01`;
    await page.locator('input[name="dependantEnrollmentDate"]').fill(backDate);

    await page.locator('.dependant-form').getByRole('button', { name: /^Add dependant$/ }).click();

    await expect.poll(() => postedDep, { timeout: 10_000 }).not.toBeNull();
    expect(postedDep).toMatchObject({
      firstName: 'Betty',
      lastName: 'Lovelace',
      memberId: member.id,
    });

    // Wait for the dependant row to appear.
    await expect(page.locator('.dependant-item', { hasText: 'Betty Lovelace' })).toBeVisible({ timeout: 10_000 });

    // Terminate — now goes through the deactivate-dependant modal
    // (replaces the old prompt() flow). Assert the modal opens, its
    // default effectiveDate is end-of-month, and submitting persists.
    await page.locator('.dependant-item', { hasText: 'Betty' }).getByRole('button', { name: /Terminate/ }).click();
    const modal = page.locator('.modal-card', { hasText: /Terminate dependant/i });
    await expect(modal).toBeVisible({ timeout: 5000 });
    const now2 = new Date();
    const eom = new Date(now2.getFullYear(), now2.getMonth() + 1, 0);
    const expectedDefault = `${eom.getFullYear()}-${String(eom.getMonth() + 1).padStart(2, '0')}-${String(eom.getDate()).padStart(2, '0')}`;
    await expect(modal.locator('[data-testid="deactivate-dependant-effective-date"]')).toHaveValue(expectedDefault);
    await modal.locator('[data-testid="deactivate-dependant-submit"]').click();

    await expect.poll(() => deactivateBody, { timeout: 10_000 }).not.toBeNull();
    expect(deactivateBody).toMatchObject({ effectiveDate: expectedDefault });
    expect(seed.dependants[0].status).toBe('deactivated');
  });
});
