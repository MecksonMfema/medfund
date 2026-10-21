import { test, expect } from '../fixtures/test';

/**
 * Phase 13 §A per L4/L5 (Phase 4). Admin lifts a LIFE policy to
 * status=lapsed via the shared PolicyStatusActionButtonsComponent +
 * PolicyStatusActionModalComponent → confirms the request body carries
 * the reason vocab + the reload picks up the new status.
 */

const POLICY_ID = '00000000-0000-0000-0000-000000000001';
const SCHEME_ID = '11111111-1111-1111-1111-111111111111';
const MEMBER_ID = '22222222-2222-2222-2222-222222222222';

function seedPolicy(status: string) {
  return {
    id: POLICY_ID,
    schemeId: SCHEME_ID,
    groupId: null,
    insuredMemberId: MEMBER_ID,
    policyNumber: 'LIFE-000001',
    sumAssured: 100_000,
    occupationHazardClass: 'SEDENTARY',
    termMonths: 12,
    status,
    billingOverrideAmount: null,
    billingOverrideReason: null,
    billingOverrideEffectiveFrom: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

test.describe('Phase 13 §A policy status workflow', () => {
  test('admin lapses a LIFE policy via the modal', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['policy:status_manage'],
    });

    let currentStatus: 'active' | 'lapsed' = 'active';
    apiMocks.respond(`GET /life-policies/${POLICY_ID}`, () => seedPolicy(currentStatus));

    // Prefill labels are best-effort and non-fatal — return empty results.
    apiMocks.respond(`GET /schemes/${SCHEME_ID}`, () => ({ id: SCHEME_ID, name: 'Silver', schemeType: 'MEDICAL' }));
    apiMocks.respond(`GET /members/${MEMBER_ID}`, () => ({
      id: MEMBER_ID, firstName: 'Ada', lastName: 'Lovelace',
    }));

    let postedBody: unknown = null;
    apiMocks.respond(`POST /life-policies/${POLICY_ID}/lapse`, (req) => {
      postedBody = req.postDataJSON();
      currentStatus = 'lapsed';
      return null;
    }, 204);

    await page.goto(`/tenant/policies/life/${POLICY_ID}`);
    await expect(page.getByRole('heading', { name: /Edit Life Policy/i })).toBeVisible();

    // Active → the buttons row shows lapse + terminate + suspend.
    const lapseBtn = page.locator('button[data-action="lapse"]');
    await expect(lapseBtn).toBeVisible();

    await lapseBtn.click();
    await expect(page.getByRole('dialog', { name: /Lapse policy/i })).toBeVisible();

    // Pick "Non-payment" from the reason vocab picker.
    await page.getByLabel(/Reason/i).click();
    await page.keyboard.type('Non-payment');
    await page.keyboard.press('Enter');

    const respWait = page.waitForResponse(r =>
      r.url().endsWith(`/api/v1/life-policies/${POLICY_ID}/lapse`) && r.request().method() === 'POST',
    );
    await page.getByRole('button', { name: /Confirm/i }).click();
    expect((await respWait).status()).toBe(204);

    expect(postedBody).toMatchObject({ reasonCode: 'NON_PAYMENT' });

    // Policy re-fetched — status chip flips to lapsed.
    await expect(page.locator('.chip.status[data-status="lapsed"]')).toBeVisible();
  });
});
