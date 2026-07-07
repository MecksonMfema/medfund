import type { Request } from '@playwright/test';
import { test, expect } from '../fixtures/test';
import { stubBillingAPIs, emptySeed, BillingGroup, BillingScheme } from '../fixtures/billing-stubs';

/**
 * Enrol a member into a group via `/tenant/members/add`, then land back on
 * the group detail page and confirm the roster reflects the new enrolment.
 * Uses the actual member-form fields with all required inputs — several
 * client-side validators would otherwise block submit.
 */
test.describe('Billing — member enrolment', () => {
  test('enrol a new member with a group + scheme; roster reflects the addition', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: [
        'billing:view', 'billing:manage_groups', 'billing:manage_members',
        'members:view', 'members:create',
      ],
    });

    const seed = emptySeed();
    const acme: BillingGroup = {
      id: 'grp-e2e-acme',
      name: 'Acme Ltd',
      registrationNumber: 'REG-001',
      status: 'ACTIVE',
    };
    const gold: BillingScheme = {
      id: 'sch-e2e-gold',
      name: 'Gold',
      schemeType: 'medical_aid',
      status: 'active',
      effectiveDate: '2025-01-01',
      endDate: null,
      currencyCode: 'USD',
      description: null,
      insuranceLine: 'HEALTH',
    };
    seed.groups.push(acme);
    seed.schemes.push(gold);
    stubBillingAPIs(apiMocks, seed);

    let postedMember: Record<string, unknown> | null = null;
    apiMocks.respond('POST /members', async (req: Request) => {
      postedMember = JSON.parse(req.postData() ?? '{}');
      const m = {
        id: 'mem-e2e-1',
        memberNumber: 'M000042',
        firstName: (postedMember as any).firstName,
        lastName: (postedMember as any).lastName,
        dateOfBirth: (postedMember as any).dateOfBirth,
        email: (postedMember as any).email,
        phone: '',
        status: 'ACTIVE',
        groupId: (postedMember as any).groupId ?? null,
        schemeId: (postedMember as any).schemeId ?? null,
        enrollmentDate: (postedMember as any).enrollmentDate,
        createdAt: new Date().toISOString(),
      };
      seed.members.push(m as any);
      return m;
    });

    await page.goto('/tenant/members/add');
    await expect(page.getByRole('heading', { name: /Enrol a new member/i })).toBeVisible({ timeout: 10_000 });

    // Identity + contact — all required by the client-side validator.
    await page.locator('input[name="firstName"]').fill('Ada');
    await page.locator('input[name="lastName"]').fill('Lovelace');
    await page.locator('input[name="dateOfBirth"]').fill('1990-01-01');
    // Gender is an <app-select> — pick anything.
    await page.locator('app-select[name="gender"] .select-trigger').click();
    await page.locator('.select-option .option-label').first().click();
    await page.locator('input[name="nationalId"]').fill('ID-0001');
    await page.locator('input[name="email"]').fill('ada@example.com');

    // Scoped to the Enrolment section — the form has two entity-pickers
    // in this order: [0]=scheme, [1]=group. Both start empty.
    const enrolmentSection = page.locator('.form-section', { hasText: /Enrol/i });
    const schemePicker = enrolmentSection.locator('app-entity-picker').nth(0);
    await schemePicker.locator('input').focus();
    const schemeResp = page.waitForResponse(r => r.url().includes('/schemes/search') && r.status() === 200);
    await schemePicker.locator('input').pressSequentially('Gold', { delay: 60 });
    await schemeResp;
    await expect(schemePicker.locator('.suggestion', { hasText: 'Gold' }).first()).toBeVisible({ timeout: 10_000 });
    await schemePicker.locator('.suggestion', { hasText: 'Gold' }).first().dispatchEvent('mousedown');

    const groupPicker = enrolmentSection.locator('app-entity-picker').nth(1);
    await groupPicker.locator('input').focus();
    const groupResp = page.waitForResponse(r => r.url().includes('/groups/search') && r.status() === 200);
    await groupPicker.locator('input').pressSequentially('Acme', { delay: 60 });
    await groupResp;
    await expect(groupPicker.locator('.suggestion', { hasText: 'Acme' }).first()).toBeVisible({ timeout: 10_000 });
    await groupPicker.locator('.suggestion', { hasText: 'Acme' }).first().dispatchEvent('mousedown');

    await page.getByRole('button', { name: /enrol member/i }).click();

    await expect.poll(() => postedMember, { timeout: 15_000 }).not.toBeNull();
    expect(postedMember).toMatchObject({
      firstName: 'Ada',
      lastName: 'Lovelace',
      dateOfBirth: '1990-01-01',
      email: 'ada@example.com',
      schemeId: gold.id,
      groupId: acme.id,
    });

    // Navigate back to the group detail; the roster serves from
    // GET /members/group/{groupId} which reflects the mutation.
    await page.goto(`/tenant/billing/groups/${acme.id}`);
    await expect(page.getByRole('heading', { name: 'Acme Ltd' })).toBeVisible();
    await expect(page.locator('table.members-table tr', { hasText: 'Ada Lovelace' })).toBeVisible({ timeout: 5000 });
  });
});
