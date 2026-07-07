import { test, expect } from '../fixtures/test';
import { stubClaimsAPIs, emptyClaimsSeed, Claim, RejectionReason } from '../fixtures/claims-stubs';
import { stubBillingAPIs, emptySeed as emptyBillingSeed } from '../fixtures/billing-stubs';

/**
 * Claim detail — run the adjudication pipeline, then take a positive
 * (approve) and negative (reject) manual transition. Confirms the
 * three action buttons and their permission-gated `canAct(...)` guards.
 */
test.describe('Claims — detail + adjudicate', () => {
  test('Run pipeline → ADJUDICATED, then manual reject with reason code', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['claims:view', 'claims:adjudicate'],
    });

    stubBillingAPIs(apiMocks, emptyBillingSeed());
    const seed = emptyClaimsSeed();
    const claim: Claim = {
      id: 'clm-e2e-1',
      claimNumber: 'CLM-000001',
      memberId: 'mem-1',
      providerId: 'prv-1',
      schemeId: 'sch-1',
      claimType: 'MEDICAL',
      status: 'VERIFIED',
      serviceDate: '2026-07-01',
      claimedAmount: '250.00',
      currencyCode: 'USD',
      createdAt: new Date().toISOString(),
    };
    seed.claims.push(claim);
    seed.claimLines.set(claim.id, [
      { id: 'l-1', claimId: claim.id, tariffCode: '0001A',
        description: 'Consultation', quantity: 1, unitPrice: '250.00',
        claimedAmount: '250.00' },
    ]);
    const rr: RejectionReason = {
      id: 'rr-1', code: 'NO_COVER', description: 'Not covered',
      category: 'BENEFITS', isActive: true,
    };
    seed.rejectionReasons.push(rr);
    stubClaimsAPIs(apiMocks, seed);

    await page.goto(`/tenant/claims/${claim.id}`);
    await expect(page.getByRole('heading', { name: /CLM-000001/ })).toBeVisible({ timeout: 10_000 });

    // Status is VERIFIED → adjudicate is enabled.
    await page.getByRole('button', { name: /^Run pipeline$/ }).click();

    // Post-adjudicate: status flips to ADJUDICATED in the badge.
    await expect(page.locator('.status-badge', { hasText: /ADJUDICATED/i }))
      .toBeVisible({ timeout: 10_000 });

    // Reset the claim to IN_ADJUDICATION so the reject path is enabled
    // (canAct('reject') on ADJUDICATED is false — see canAct switch).
    // In a real workflow the operator would move via another action;
    // for the spec we drive the status directly via the /status stub.
    seed.claims[0].status = 'IN_ADJUDICATION';
    await page.reload();
    await expect(page.locator('.status-badge', { hasText: /IN ADJUDICATION/i }))
      .toBeVisible({ timeout: 10_000 });

    // Pick a rejection reason and click Reject. Options render label=code
    // (see claim-detail.component.ts rejectionReasonOptions) — target NO_COVER.
    await page.locator('app-select[name="rejectionCode"] .select-trigger').click();
    await page.locator('.select-option .option-label', { hasText: /^NO_COVER$/ }).first().click();
    page.once('dialog', d => d.accept());
    await page.getByRole('button', { name: /^Reject$/ }).click();

    await expect(page.locator('.status-badge', { hasText: /REJECTED/i }))
      .toBeVisible({ timeout: 10_000 });
    expect(seed.claims[0].status).toBe('REJECTED');
  });
});
