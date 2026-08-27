import { test, expect } from '../fixtures/test';
import {
  stubClaimsAPIs,
  emptyClaimsSeed,
  Claim,
} from '../fixtures/claims-stubs';
import { stubBillingAPIs, emptySeed as emptyBillingSeed } from '../fixtures/billing-stubs';

/**
 * Phase 14 §A — case-reserve capture on the claim detail page.
 * Adjudicator with claims:set_reserve opens the modal, saves a reserve,
 * and sees the history table populate with the new row.
 */

interface ReserveRow {
  id: string;
  claimId: string;
  reservedAmount: string;
  effectiveAt: string;
  actorEmail: string;
  reasonNote: string;
}

test.describe('Claims — case reserve workflow (Phase 14 §A)', () => {
  test('set reserve → history renders → update reserve replaces the top row', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['claims:view', 'claims:adjudicate', 'claims:set_reserve'],
    });

    stubBillingAPIs(apiMocks, emptyBillingSeed());
    const seed = emptyClaimsSeed();
    const claim: Claim = {
      id: 'clm-reserve-1',
      claimNumber: 'CLM-000042',
      memberId: 'mem-1',
      providerId: 'prv-1',
      schemeId: 'sch-1',
      claimType: 'MEDICAL',
      status: 'VERIFIED',
      serviceDate: '2026-07-01',
      claimedAmount: '1000.00',
      currencyCode: 'USD',
      createdAt: new Date().toISOString(),
    };
    seed.claims.push(claim);
    seed.claimLines.set(claim.id, []);
    stubClaimsAPIs(apiMocks, seed);

    // Stateful reserve backend — new rows push onto the head so the UI
    // sees them newest-first (matches server ORDER BY effective_at DESC).
    const reserveRows: ReserveRow[] = [];
    apiMocks.respond(`GET /claims/${claim.id}/reserve/history`, () => reserveRows);
    apiMocks.respond(`POST /claims/${claim.id}/reserve`, async (req) => {
      const body = JSON.parse(req.postData() ?? '{}');
      const row: ReserveRow = {
        id: `res-${reserveRows.length + 1}`,
        claimId: claim.id,
        reservedAmount: body.reservedAmount,
        effectiveAt: new Date().toISOString(),
        actorEmail: 'operator@example.com',
        reasonNote: body.reasonNote,
      };
      reserveRows.unshift(row);
      return row;
    }, 201);

    await page.goto(`/tenant/claims/${claim.id}`);
    await expect(page.getByRole('heading', { name: /CLM-000042/ })).toBeVisible({ timeout: 10_000 });

    // Empty-state message on the history card.
    await expect(page.getByText('No reserve has been set for this claim yet.')).toBeVisible();

    // Open the modal — the button reads "Set reserve" when the claim has none.
    await page.getByRole('button', { name: /^Set reserve$/ }).click();

    // Modal is up — fill amount + reason, click save.
    const modal = page.getByRole('dialog', { name: /Set claim reserve/ });
    await expect(modal).toBeVisible();
    await modal.locator('input[name="reservedAmount"]').fill('500');
    await modal.locator('textarea[name="reasonNote"]').fill('First-pass estimate on this claim');
    await modal.getByRole('button', { name: /^Save reserve$/ }).click();

    // History table replaces the empty-state and shows our new row.
    await expect(page.getByText('First-pass estimate on this claim')).toBeVisible({ timeout: 5_000 });
    // Button label flips to "Update reserve" once a row exists.
    await expect(page.getByRole('button', { name: /^Update reserve$/ })).toBeVisible();

    // Update the reserve — new row goes on top.
    await page.getByRole('button', { name: /^Update reserve$/ }).click();
    const modal2 = page.getByRole('dialog', { name: /Set claim reserve/ });
    await expect(modal2).toBeVisible();
    await modal2.locator('input[name="reservedAmount"]').fill('750');
    await modal2.locator('textarea[name="reasonNote"]').fill('Revised after triage');
    await modal2.getByRole('button', { name: /^Save reserve$/ }).click();

    await expect(page.getByText('Revised after triage')).toBeVisible({ timeout: 5_000 });
    // Both rows are present in the history table.
    await expect(page.getByText('First-pass estimate on this claim')).toBeVisible();
  });
});
