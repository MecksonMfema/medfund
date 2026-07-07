import type { Request } from '@playwright/test';
import { test, expect } from '../fixtures/test';
import { stubBillingAPIs, emptySeed, BillingScheme, BillingMember } from '../fixtures/billing-stubs';

/**
 * End-to-end for the "Change scheme" modal wired into the member detail
 * page. The old profile-form-picker bypass was closed — the scheme
 * picker inside the enrolment form is read-only, and PUT /members/:id
 * no longer accepts a differing schemeId. The only supported path is:
 *
 *   Header button "Change scheme"
 *     → Modal picks target + effective date
 *     → POST /scheme-changes {memberId, fromSchemeId, toSchemeId, effectiveDate, reason?}
 */
test.describe('Member operations — scheme change modal', () => {
  test('forward-dated request lands as PENDING via /scheme-changes', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['members:view', 'members:update', 'billing:manage_members'],
    });

    const seed = emptySeed();
    const oldScheme: BillingScheme = {
      id: 'sch-old',
      name: 'Standard',
      schemeType: 'medical_aid',
      status: 'active',
      effectiveDate: '2025-01-01', endDate: null,
      currencyCode: 'USD', description: null, insuranceLine: 'HEALTH',
    };
    const newScheme: BillingScheme = {
      id: 'sch-new',
      name: 'Gold Plus',
      schemeType: 'medical_aid',
      status: 'active',
      effectiveDate: '2025-01-01', endDate: null,
      currencyCode: 'USD', description: null, insuranceLine: 'HEALTH',
    };
    seed.schemes.push(oldScheme, newScheme);
    const member: BillingMember = {
      id: 'mem-e2e-1',
      memberNumber: 'M000001',
      firstName: 'Ada', lastName: 'Lovelace',
      dateOfBirth: '1990-01-01',
      email: 'ada@example.com', phone: '',
      status: 'ACTIVE',
      groupId: null,
      schemeId: oldScheme.id,
      enrollmentDate: '2025-01-01',
      createdAt: '2025-01-01T00:00:00Z',
    };
    seed.members.push(member);
    stubBillingAPIs(apiMocks, seed);

    let postBody: Record<string, unknown> | null = null;
    apiMocks.respond('POST /scheme-changes', async (req: Request) => {
      postBody = JSON.parse(req.postData() ?? '{}');
      const sc = {
        id: 'sch-change-1',
        memberId: (postBody as any).memberId,
        fromSchemeId: (postBody as any).fromSchemeId,
        toSchemeId: (postBody as any).toSchemeId,
        status: 'PENDING',
        requestedDate: new Date().toISOString().slice(0, 10),
        effectiveDate: (postBody as any).effectiveDate,
        reason: (postBody as any).reason ?? null,
        changeKind: 'CROSS_GRADE',
        appliedAt: null,
      };
      seed.schemeChanges.push(sc as any);
      return sc;
    });

    await page.goto(`/tenant/members/${member.id}`);
    await expect(page.getByRole('heading', { name: /Ada/ })).toBeVisible({ timeout: 10_000 });

    // Header button opens the dedicated modal — the profile-form
    // scheme picker is read-only precisely to force operators here.
    await page.getByRole('button', { name: /change scheme/i }).click();

    const modal = page.getByRole('dialog', { name: /change scheme/i });
    await expect(modal).toBeVisible();

    // Modal has a single target-scheme EntityPicker. Search by name
    // so the payload holds an ID but the UI shows a name
    // (feedback_no_raw_id_inputs).
    const targetPicker = modal.locator('app-entity-picker');
    await targetPicker.locator('input').focus();
    const searchResp = page.waitForResponse(r => r.url().includes('/schemes/search') && r.status() === 200);
    await targetPicker.locator('input').pressSequentially('Gold', { delay: 60 });
    await searchResp;
    await expect(targetPicker.locator('.suggestion', { hasText: 'Gold Plus' })).toBeVisible({ timeout: 10_000 });
    await targetPicker.locator('.suggestion', { hasText: 'Gold Plus' }).first().dispatchEvent('mousedown');

    // Effective date defaults to next month's 1st — leave it as-is.
    await modal.getByRole('button', { name: /book change/i }).click();

    await expect.poll(() => postBody, { timeout: 10_000 }).not.toBeNull();
    expect((postBody as any).memberId).toBe(member.id);
    expect((postBody as any).fromSchemeId).toBe(oldScheme.id);
    expect((postBody as any).toSchemeId).toBe(newScheme.id);
    // Forward-dated → PENDING; members.schemeId stays on the old
    // scheme until the workflow applies.
    expect(member.schemeId).toBe(oldScheme.id);
  });

  test('profile-form scheme picker is read-only (bypass closed)', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['members:view', 'members:update', 'billing:manage_members'],
    });

    const seed = emptySeed();
    const scheme: BillingScheme = {
      id: 'sch-1',
      name: 'Standard',
      schemeType: 'medical_aid',
      status: 'active',
      effectiveDate: '2025-01-01', endDate: null,
      currencyCode: 'USD', description: null, insuranceLine: 'HEALTH',
    };
    seed.schemes.push(scheme);
    const member: BillingMember = {
      id: 'mem-e2e-2',
      memberNumber: 'M000002',
      firstName: 'Grace', lastName: 'Hopper',
      dateOfBirth: '1985-01-01',
      email: 'grace@example.com', phone: '',
      status: 'ACTIVE',
      groupId: null,
      schemeId: scheme.id,
      enrollmentDate: '2025-01-01',
      createdAt: '2025-01-01T00:00:00Z',
    };
    seed.members.push(member);
    stubBillingAPIs(apiMocks, seed);

    await page.goto(`/tenant/members/${member.id}`);
    await expect(page.getByRole('heading', { name: /Grace/ })).toBeVisible({ timeout: 10_000 });

    // The Enrolment section's second EntityPicker is the scheme picker.
    // With a scheme already assigned the picker starts in `picked` state:
    // no <input>, just a "Change" button. It must be disabled — editing is
    // gated through the header "Change scheme" modal instead.
    const enrolment = page.locator('.form-section', { hasText: /Enrol/i });
    const schemePicker = enrolment.locator('app-entity-picker').nth(1);
    const changeBtn = schemePicker.locator('button.link-btn', { hasText: /Change/i });
    await expect(changeBtn).toBeVisible();
    await expect(changeBtn).toBeDisabled();
  });
});
