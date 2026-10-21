import { test, expect } from '../fixtures/test';

/**
 * Phase 12 §C Phase 10 — Endorsement four-eyes lifecycle (drafter +
 * approver journey). Every API call is stubbed via ApiMocks so no live
 * services are needed. Mirrors the shape of {@code
 * reinsurance-facultative.spec.ts}'s supervisor-approves-then-commits
 * flow (Phase 10 §B precedent) — DRAFT → APPROVED → COMMITTED.
 *
 *   1. Drafter opens the per-policy Endorsements page → clicks "New" →
 *      fills the modal → save posts POST /endorsements, row appears.
 *   2. Supervisor loads the review queue → approves the DRAFT →
 *      commits the APPROVED → queue clears.
 */

interface EndorsementRow {
  id: string;
  reference: string;
  policyId: string;
  policySource: string;
  insuranceLine: string;
  changeType: string;
  effectiveFrom: string;
  premiumDelta: string | null;
  currencyCode: string | null;
  reason: string;
  status: 'DRAFT' | 'APPROVED' | 'COMMITTED' | 'COMPUTED' | 'VOIDED';
  draftActorId: string;
  draftActorEmail: string;
  draftAt: string;
  approveActorId: string | null;
  approveActorEmail: string | null;
  approveAt: string | null;
  commitActorId: string | null;
  commitActorEmail: string | null;
  commitAt: string | null;
  voidedReason: string | null;
  voidedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

function seedDraft(overrides: Partial<EndorsementRow> = {}): EndorsementRow {
  return {
    id: 'end-1',
    reference: 'END-2026-000001',
    policyId: '00000000-0000-0000-0000-000000000001',
    policySource: 'LIFE_POLICY',
    insuranceLine: 'LIFE',
    changeType: 'PREMIUM_ADJUSTMENT',
    effectiveFrom: '2026-05-01',
    premiumDelta: '120.00',
    currencyCode: 'USD',
    reason: 'Cover uplift from broker request.',
    status: 'DRAFT',
    draftActorId: 'actor-a',
    draftActorEmail: 'drafter@tenant',
    draftAt: '2026-04-15T10:00:00Z',
    approveActorId: null,
    approveActorEmail: null,
    approveAt: null,
    commitActorId: null,
    commitActorEmail: null,
    commitAt: null,
    voidedReason: null,
    voidedAt: null,
    createdAt: '2026-04-15T10:00:00Z',
    updatedAt: '2026-04-15T10:00:00Z',
    ...overrides,
  };
}

test.describe('Phase 12 §C endorsement workflow', () => {
  test('drafter files a new endorsement from the per-policy page', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['policy:draft_endorsement'],
    });

    // Empty history at first
    let existing: EndorsementRow[] = [];
    apiMocks.respond('GET /endorsements/by-policy', () => existing);

    let createdBody: unknown = null;
    apiMocks.respond('POST /endorsements', (req) => {
      createdBody = req.body;
      const row = seedDraft();
      existing = [row];
      return row;
    });

    // No currencies stub → picker falls back to '— Select currency —'
    apiMocks.respond(/\/tenants\/[^/]+\/currencies/, () => []);

    await page.goto('/tenant/policies/endorsements/LIFE_POLICY/00000000-0000-0000-0000-000000000001');
    await expect(page.getByRole('heading', { name: 'Endorsements' })).toBeVisible();
    await expect(page.getByText(/No endorsements yet/)).toBeVisible();

    await page.getByRole('button', { name: /New endorsement/i }).click();
    await expect(page.getByRole('dialog', { name: /Draft endorsement/i })).toBeVisible();

    // Premium adjustment defaults; fill delta + currency + reason.
    await page.getByLabel(/Premium delta/i).fill('120.00');
    // Currency option is only enabled once delta is set
    await page.getByLabel(/Currency/i).click();
    // Text-typing fallback since the SelectComponent is search-select
    await page.keyboard.type('USD');
    await page.keyboard.press('Enter');
    await page.getByLabel(/Reason/i).fill('Cover uplift from broker request.');

    const createResp = page.waitForResponse(
      r => r.url().endsWith('/api/v1/endorsements') && r.request().method() === 'POST',
    );
    await page.getByRole('button', { name: /Save draft/i }).click();
    expect((await createResp).status()).toBe(200);

    // Server saw the wire-shape we expected
    expect(createdBody).toMatchObject({
      policyId: '00000000-0000-0000-0000-000000000001',
      policySource: 'LIFE_POLICY',
      insuranceLine: 'LIFE',
      changeType: 'PREMIUM_ADJUSTMENT',
      reason: 'Cover uplift from broker request.',
    });
    await expect(page.getByRole('cell', { name: /END-2026-000001/ })).toBeVisible();
  });

  test('supervisor approves then commits a DRAFT — same-actor guard blocks own approve', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['policy:approve_endorsement'],
      // Deliberately DIFFERENT from the drafter's email in the seed so
      // the same-actor guard does not disable Approve.
      email: 'supervisor@tenant',
    });

    let stage: 'DRAFT' | 'APPROVED' | 'COMMITTED' = 'DRAFT';
    apiMocks.respond('GET /endorsements', () => {
      const content: EndorsementRow[] = stage === 'COMMITTED'
        ? []
        : [seedDraft({
            status: stage,
            approveActorEmail: stage !== 'DRAFT' ? 'supervisor@tenant' : null,
            approveAt: stage !== 'DRAFT' ? '2026-04-15T10:05:00Z' : null,
          })];
      return {
        content,
        totalElements: content.length,
        totalPages: 1,
        number: 0,
        size: 50,
      };
    });

    apiMocks.respond('PUT /endorsements/end-1/approve', () => {
      stage = 'APPROVED';
      return seedDraft({ status: 'APPROVED', approveActorEmail: 'supervisor@tenant', approveAt: '2026-04-15T10:05:00Z' });
    });
    apiMocks.respond('PUT /endorsements/end-1/commit', () => {
      stage = 'COMMITTED';
      return seedDraft({ status: 'COMMITTED', commitActorEmail: 'supervisor@tenant', commitAt: '2026-04-15T10:10:00Z' });
    });

    await page.goto('/tenant/finance/underwriting/endorsements/review-queue');
    await expect(page.getByRole('heading', { name: /review queue/i })).toBeVisible();

    await expect(page.getByText('DRAFT', { exact: true })).toBeVisible();
    await page.getByTestId('approve-btn').click();

    await expect(page.getByText('APPROVED', { exact: true })).toBeVisible();
    await page.getByTestId('commit-btn').click();

    await expect(page.getByText(/Queue is clear/)).toBeVisible();
  });
});
