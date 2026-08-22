import { test, expect } from '../fixtures/test';

/**
 * Phase 10 §B — Facultative-cession lifecycle. Two Playwright scenarios:
 *
 *   1. Underwriter browses candidates → picks one → drops a DRAFT.
 *   2. Supervisor loads the queue → approves → commits → queue shrinks.
 *
 * Both scenarios stub every /api/v1 call — no live services needed.
 */

interface CessionRow {
  id: string;
  treatyId: string;
  treatyLayerId?: string | null;
  cessionType: 'LOSS' | 'PREMIUM';
  source: 'AUTOMATIC' | 'FACULTATIVE';
  status: 'DRAFT' | 'APPROVED' | 'CEDED' | 'VOIDED' | 'ACTIVE';
  sourceEventId: string;
  sourceEventType: string;
  cededAmount: number;
  currencyCode: string;
  basisAmount: number;
  occurredAt: string;
  voidedReason?: string | null;
  createdAt: string;
  updatedAt: string;
}

function seededTreaty() {
  return {
    id: 't-1', treatyRef: 'HEALTH-QS-2026', treatyType: 'QUOTA_SHARE',
    declaredCurrency: 'USD',
    inceptionDate: '2026-01-01', expiryDate: '2026-12-31', status: 'ACTIVE',
    renewedFromTreatyId: null,
    aggregateLimit: null, aggregateLimitCurrency: null,
    expectedAnnualPremium: null, producerRef: null,
    activatedAt: '2026-01-01T00:00:00Z',
    createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
  };
}

test.describe('Reinsurance facultative (§B)', () => {
  test('underwriter drafts a facultative cession from a candidate', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance.reinsurance:view', 'finance.reinsurance:cede_facultative'],
    });

    apiMocks.respond('GET /reinsurance/treaties', () => ({
      content: [seededTreaty()],
      total: 1, page: 0, size: 200, totalPages: 1,
    }));

    // One candidate above threshold
    apiMocks.respond('GET /reinsurance/facultative/candidates', () => [
      {
        claimId: 'cl-1', claimNumber: 'CLM-1001',
        memberId: 'm-1', memberName: 'John Doe',
        providerId: 'p-1', providerName: 'City Hospital',
        insuranceLine: 'HEALTH',
        approvedAmount: 25000, currencyCode: 'USD',
        submissionDate: '2026-08-01T10:00:00Z',
        alreadyCeded: false,
      },
    ]);

    let createdPayload: unknown = null;
    apiMocks.respond('POST /reinsurance/facultative', (req) => {
      createdPayload = req.body;
      const body = req.body as { claimId: string; treatyId: string; cededAmount: number; basisAmount: number };
      return {
        id: 'ce-1', treatyId: body.treatyId, treatyLayerId: null,
        cessionType: 'LOSS', source: 'FACULTATIVE', status: 'DRAFT',
        sourceEventId: body.claimId, sourceEventType: 'CLAIM_FACULTATIVE',
        cededAmount: body.cededAmount, currencyCode: 'USD',
        basisAmount: body.basisAmount,
        occurredAt: '2026-08-22T10:00:00Z',
        createdAt: '2026-08-22T10:00:00Z', updatedAt: '2026-08-22T10:00:00Z',
      } as CessionRow;
    });

    await page.goto('/tenant/finance/reinsurance/facultative/browse');
    await expect(page.getByRole('heading', { name: 'Facultative — browse candidates' })).toBeVisible();

    // Candidate row surfaces
    await expect(page.getByRole('cell', { name: 'CLM-1001' })).toBeVisible();

    // Click Cede — cede pane populates
    await page.getByRole('button', { name: 'Cede' }).click();
    await expect(page.getByText('Cede claim CLM-1001')).toBeVisible();

    // Pick treaty via search-select
    await page.getByLabel('Treaty').click();
    await page.getByRole('option', { name: /HEALTH-QS-2026/ }).click();

    // Fill amounts
    await page.getByTestId('ceded-amount-input').fill('7500');
    // basisAmount is pre-filled from the candidate — trust it and submit.

    const createResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reinsurance/facultative') && r.request().method() === 'POST',
    );
    await page.getByTestId('submit-cede').click();
    expect((await createResp).status()).toBe(200);

    // Backend received the expected payload
    expect(createdPayload).toMatchObject({
      claimId: 'cl-1', treatyId: 't-1', cededAmount: 7500,
    });
    await expect(page.getByText(/Draft cession/)).toBeVisible();
  });

  test('supervisor approves then commits a queued draft', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance.reinsurance:view', 'finance.reinsurance:approve_facultative'],
    });

    // Queue starts with one DRAFT — after approve it becomes APPROVED; after
    // commit it drops off the list entirely.
    let stage: 'DRAFT' | 'APPROVED' | 'CEDED' = 'DRAFT';
    apiMocks.respond('GET /reinsurance/facultative/queue', () => {
      const content: CessionRow[] = stage === 'CEDED' ? [] : [{
        id: 'ce-1', treatyId: 't-1', treatyLayerId: null,
        cessionType: 'LOSS', source: 'FACULTATIVE', status: stage,
        sourceEventId: 'cl-1', sourceEventType: 'CLAIM_FACULTATIVE',
        cededAmount: 7500, currencyCode: 'USD', basisAmount: 25000,
        occurredAt: '2026-08-22T10:00:00Z',
        createdAt: '2026-08-22T10:00:00Z', updatedAt: '2026-08-22T10:00:00Z',
      }];
      return {
        content, total: content.length, page: 0, size: 50,
        totalPages: content.length > 0 ? 1 : 1,
      };
    });

    apiMocks.respond('PUT /reinsurance/facultative/ce-1/approve', () => {
      stage = 'APPROVED';
      return { id: 'ce-1', status: 'APPROVED' };
    });
    apiMocks.respond('PUT /reinsurance/facultative/ce-1/commit', () => {
      stage = 'CEDED';
      return { id: 'ce-1', status: 'CEDED' };
    });

    await page.goto('/tenant/finance/reinsurance/facultative/queue');
    await expect(page.getByRole('heading', { name: 'Facultative — approve queue' })).toBeVisible();

    // DRAFT row present, Approve visible
    await expect(page.getByText('DRAFT', { exact: true })).toBeVisible();
    await page.getByTestId('approve-btn').click();

    // Row updates to APPROVED and Commit surfaces
    await expect(page.getByText('APPROVED', { exact: true })).toBeVisible();
    await page.getByTestId('commit-btn').click();

    // Queue clears
    await expect(page.getByText(/Queue is clear/)).toBeVisible();
  });
});
