import type { Request } from '@playwright/test';
import { test, expect } from '../fixtures/test';
import { stubBillingAPIs, emptySeed, BillingMember, BillingDependant } from '../fixtures/billing-stubs';

/**
 * Swap principal ⇄ dependant: promote a dependant to principal via the
 * `POST /members/{id}/swaps` API. Exercises the modal path
 * (shared/components/swap-dependant-modal) and asserts the payload.
 */
test.describe('Member operations — dependant swap', () => {
  test('book a swap that promotes a dependant to principal', async ({ page, apiMocks, signInAs }) => {
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
    const dep: BillingDependant = {
      id: 'dep-e2e-1',
      memberId: member.id,
      firstName: 'Betty', lastName: 'Lovelace',
      dateOfBirth: '2015-05-01',
      gender: 'F',
      relationship: 'Daughter',
      status: 'active',
    };
    seed.members.push(member);
    seed.dependants.push(dep);
    stubBillingAPIs(apiMocks, seed);

    let postedSwap: Record<string, unknown> | null = null;
    apiMocks.respond('POST /members/:id/swaps', async (req: Request) => {
      postedSwap = JSON.parse(req.postData() ?? '{}');
      const sw = {
        id: 'swp-e2e-1',
        oldMemberId: member.id,
        dependantId: (postedSwap as any).dependantId,
        newMemberId: null,
        oldDependantId: null,
        status: 'PENDING' as const,
        requestedDate: new Date().toISOString().slice(0, 10),
        effectiveDate: (postedSwap as any).effectiveDate,
        reason: (postedSwap as any).reason ?? null,
      };
      seed.swaps.push(sw);
      return sw;
    });

    await page.goto(`/tenant/members/${member.id}`);
    await expect(page.getByRole('heading', { name: /Ada/ })).toBeVisible({ timeout: 10_000 });

    await page.getByRole('button', { name: /Swap with dependant/i }).click();
    const modal = page.locator('.modal-card', { hasText: /Swap principal and dependant/i });
    await expect(modal).toBeVisible();

    // Pick the dependant via the radio row.
    await modal.locator('label.dep-row', { hasText: 'Betty' }).locator('input[type="radio"]').check();

    // Effective date — anything in this month. The modal's blur handler
    // snaps to the 1st of the chosen month (V046/V047 anchoring), so we
    // assert against the snapped value.
    const now = new Date();
    const dateInMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-15`;
    const firstOfMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`;
    await modal.locator('input[type="date"]').fill(dateInMonth);

    await modal.getByRole('button', { name: /Book swap/i }).click();

    await expect.poll(() => postedSwap, { timeout: 10_000 }).not.toBeNull();
    expect(postedSwap).toMatchObject({
      dependantId: dep.id,
      effectiveDate: firstOfMonth,
    });
    expect(seed.swaps).toHaveLength(1);
    expect(seed.swaps[0].oldMemberId).toBe(member.id);
  });
});
