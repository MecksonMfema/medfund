import { test, expect } from '../fixtures/test';

/**
 * Phase 10 §B (Phase 8) — Reinsurance review queue. The supervisor
 * loads the queue populated by regression detection, resolves one task
 * with RESOLVED_VOID; the queue clears.
 */

interface ReviewTaskRow {
  id: string;
  taskType: 'CLAIM_REGRESSION' | 'RECOVERY_DISPUTE' | 'MANUAL_VOID_REQUEST';
  cessionId: string | null;
  recoveryId: string | null;
  claimId: string | null;
  treatyId: string | null;
  status: 'OPEN' | 'IN_PROGRESS' | 'RESOLVED_VOID' | 'RESOLVED_KEEP' | 'DISMISSED';
  assigneeUserId: string | null;
  dueBy: string | null;
  createReason: string;
  resolutionNotes: string | null;
  createdAt: string;
  updatedAt: string;
}

test.describe('Reinsurance review queue (Phase 8)', () => {
  test('supervisor resolves a regression task and the queue clears', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance.reinsurance:view', 'finance.reinsurance:resolve_review'],
    });

    let stage: 'OPEN' | 'RESOLVED_VOID' = 'OPEN';
    apiMocks.respond('GET /reinsurance/review-tasks', () => {
      const content: ReviewTaskRow[] = stage === 'OPEN' ? [{
        id: 't-1',
        taskType: 'CLAIM_REGRESSION',
        cessionId: 'ce-1',
        recoveryId: null,
        claimId: 'cl-1',
        treatyId: 'tr-1',
        status: 'OPEN',
        assigneeUserId: null,
        dueBy: null,
        createReason: 'Claim cl-1 re-adjudicated: previous basis 1000.00 USD → new basis 500.00 USD',
        resolutionNotes: null,
        createdAt: '2026-08-22T10:00:00Z',
        updatedAt: '2026-08-22T10:00:00Z',
      }] : [];
      return {
        content,
        total: content.length,
        page: 0,
        size: 50,
        totalPages: content.length > 0 ? 1 : 0,
      };
    });

    let resolvePayload: unknown = null;
    apiMocks.respond('POST /reinsurance/review-tasks/t-1/resolve', (req) => {
      resolvePayload = req.body;
      stage = 'RESOLVED_VOID';
      return {
        id: 't-1',
        taskType: 'CLAIM_REGRESSION',
        cessionId: 'ce-1',
        recoveryId: null,
        claimId: 'cl-1',
        treatyId: 'tr-1',
        status: 'RESOLVED_VOID',
        assigneeUserId: null,
        dueBy: null,
        createReason: 'Claim cl-1 re-adjudicated',
        resolutionNotes: 'wrong loss magnitude',
        createdAt: '2026-08-22T10:00:00Z',
        updatedAt: '2026-08-22T11:00:00Z',
      } as ReviewTaskRow;
    });

    await page.goto('/tenant/finance/reinsurance/review-queue');
    await expect(page.getByRole('heading', { name: 'Reinsurance — review queue' })).toBeVisible();

    // Task row surfaces with OPEN badge.
    await expect(page.getByTestId('review-queue-table')).toBeVisible();
    await expect(page.getByText('Open', { exact: true })).toBeVisible();
    await expect(page.getByText(/re-adjudicated/)).toBeVisible();

    // Open resolve modal, pick VOID, add notes, submit.
    await page.getByTestId('resolve-btn').click();
    await expect(page.getByTestId('resolve-modal')).toBeVisible();

    await page.getByLabel('Resolution').click();
    await page.getByRole('option', { name: /Void cession/i }).click();
    await page.getByTestId('resolve-notes').fill('wrong loss magnitude');

    const resolveResp = page.waitForResponse(
      r => r.url().includes('/api/v1/reinsurance/review-tasks/t-1/resolve')
        && r.request().method() === 'POST',
    );
    await page.getByTestId('resolve-submit').click();
    expect((await resolveResp).status()).toBe(200);
    expect(resolvePayload).toMatchObject({
      resolution: 'RESOLVED_VOID',
      notes: 'wrong loss magnitude',
    });

    // Queue empties after the mock swap.
    await expect(page.getByText(/No tasks match/)).toBeVisible();
  });
});
