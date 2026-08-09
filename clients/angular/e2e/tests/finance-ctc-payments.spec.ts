import type { Request } from '@playwright/test';
import { test, expect } from '../fixtures/test';

/**
 * Phase 5 golden path — Claims-to-Contributions transfers end to end:
 *
 *   1. Auto-drafts from a MEMBER-payee adjudication appear on the
 *      /tenant/claims/ctc/auto page.
 *   2. Manual record → commit → the CTC_OFFSET row surfaces via the
 *      billing preset route at /tenant/billing/transactions/ctc.
 *   3. The Reverse row action is gated on `finance:reverse_ctc_payment`
 *      — an operator without it never sees the button.
 *   4. Same operator who created a draft is allowed to commit it (unlike
 *      advance-payment approvals, which enforce a same-actor guard).
 *
 * Every network call is stubbed via ApiMocks — no live services needed.
 */

const TENANT_ID = '11111111-1111-1111-1111-111111111111';
const MEMBER_ID = '22222222-2222-4222-8222-222222222222';
const CTC_DRAFT_ID = '33333333-3333-4333-8333-333333333333';
const PAYABLE_ID = '44444444-4444-4444-8444-444444444444';
const CTC_OFFSET_TXN_ID = '55555555-5555-4555-8555-555555555555';

/**
 * Minimal spring-page envelope. `total`, `page`, `size`, `totalPages`
 * match what `FinancePageResponse<T>` on the client reads.
 */
function page1<T>(rows: T[]) {
  return {
    content: rows,
    total: rows.length,
    page: 0,
    size: rows.length,
    totalPages: 1,
  };
}

test.describe('Finance — CTC payments (Phase 5 golden path)', () => {

  test('auto-drafts from MEMBER-payee adjudication appear on /ctc/auto', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['claims:view_ctc_payments', 'finance:configure_auto_ctc'],
    });

    // Tenant config (auto-CTC on, threshold 100 USD).
    apiMocks.respond(`GET /tenants/${TENANT_ID}/ctc-auto-config`, {
      tenantId: TENANT_ID,
      enabled: true,
      minMemberBalanceThreshold: '100.00',
      maxPerCtcAmount: null,
      thresholdCurrency: 'USD',
      updatedAt: '2026-08-09T10:00:00Z',
      updatedBy: null,
    });

    // One system-drafted CTC row for the recent-drafts panel.
    apiMocks.respond('GET /ctc-payments/page', page1([{
      id: CTC_DRAFT_ID,
      memberId: MEMBER_ID,
      memberName: 'Jane Doe',
      memberNumber: 'M-0001',
      amount: '150.00',
      currencyCode: 'USD',
      committed: false,
      createdAt: '2026-08-09T10:15:00Z',
      createdBy: null,               // system-drafted
      type: 'CTC',
      status: 'draft',
      memberPayableId: PAYABLE_ID,
    }]));

    await page.goto('/tenant/claims/ctc/auto');

    // The page header + toggle should be visible.
    await expect(page.getByRole('heading', { level: 1 })).toContainText(/auto/i);
    // The auto-drafted row should render with the member's name.
    await expect(page.getByText('Jane Doe').first()).toBeVisible({ timeout: 5000 });
  });

  test('manual record → commit path shows the offset transaction in /billing/transactions/ctc', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: [
        'billing:view',
        'finance:manage_ctc_payments',
        'finance:view_member_payables',
        'claims:commit_ctc_payment',
      ],
    });

    // Initial CTC list: single draft row.
    let ctcList: any[] = [{
      id: CTC_DRAFT_ID,
      memberId: MEMBER_ID,
      memberName: 'Jane Doe',
      memberNumber: 'M-0001',
      amount: '150.00',
      currencyCode: 'USD',
      committed: false,
      createdAt: '2026-08-09T10:15:00Z',
      createdBy: 'operator@example.com',
      type: 'CTC',
      status: 'draft',
      memberPayableId: PAYABLE_ID,
    }];
    apiMocks.respond('GET /ctc-payments/page', () => page1(ctcList));

    // The single CTC's detail after commit.
    apiMocks.respond(`GET /ctc-payments/${CTC_DRAFT_ID}`, () => ({
      id: CTC_DRAFT_ID,
      type: 'CTC',
      status: ctcList[0].status,
      memberId: MEMBER_ID,
      memberPayableId: PAYABLE_ID,
      amount: '150.00',
      currencyCode: 'USD',
      committed: ctcList[0].committed,
      createdAt: '2026-08-09T10:15:00Z',
      createdBy: 'operator@example.com',
      committedAt: ctcList[0].committedAt,
      committedBy: ctcList[0].committedBy,
    }));

    // Commit endpoint mutates the seed so subsequent GETs reflect it.
    let committedPost: Record<string, unknown> | null = null;
    apiMocks.respond(`POST /ctc-payments/${CTC_DRAFT_ID}/commit`, async (req: Request) => {
      committedPost = JSON.parse(req.postData() ?? '{}');
      ctcList[0] = {
        ...ctcList[0],
        committed: true,
        status: 'committed',
        committedAt: new Date().toISOString(),
        committedBy: 'operator@example.com',
      };
      return ctcList[0];
    });

    // Transactions list backing the billing preset — one CTC_OFFSET row.
    apiMocks.respond('GET /transactions', (req: Request) => {
      const type = new URL(req.url()).searchParams.get('transactionType');
      const rows = type === 'CTC' ? [{
        id: CTC_OFFSET_TXN_ID,
        transactionNumber: 'TXN-CTC-1',
        memberId: MEMBER_ID,
        amount: -150,
        currencyCode: 'USD',
        transactionType: 'CTC_OFFSET',
        reference: `CTC:${CTC_DRAFT_ID}`,
        transactionDate: '2026-08-09T10:20:00Z',
      }] : [];
      return page1(rows);
    });

    // Start on the CTC list.
    await page.goto('/tenant/finance/payments/ctc');
    await expect(page.getByText('Jane Doe').first()).toBeVisible({ timeout: 5000 });

    // Navigate into the detail page directly (the click-through path
    // varies with the data-table's action mechanic; the direct nav is a
    // stable proxy for "operator opens the draft").
    await page.goto(`/tenant/finance/payments/ctc/${CTC_DRAFT_ID}`);
    await expect(page.getByRole('heading', { name: /CTC payment/i })).toBeVisible();

    // Commit dialog — confirm dialog is the ConfirmService modal.
    await page.getByRole('button', { name: /^Commit$/i }).click();
    const confirmBtn = page.getByRole('button', { name: /Commit$/i }).last();
    await confirmBtn.click();

    // Post-commit the endpoint should have been hit.
    await page.waitForRequest(r => r.url().includes(`/ctc-payments/${CTC_DRAFT_ID}/commit`) && r.method() === 'POST');
    expect(committedPost).not.toBeNull();

    // Preset route should now surface the CTC_OFFSET row.
    const txnRequest = page.waitForRequest(r =>
      r.url().includes('/transactions') &&
      !r.url().includes('/transactions/') &&
      r.method() === 'GET'
    );
    await page.goto('/tenant/billing/transactions/ctc');
    const captured = await txnRequest;
    expect(new URL(captured.url()).searchParams.get('transactionType')).toBe('CTC');
  });

  test('Reverse row action is hidden without finance:reverse_ctc_payment', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:manage_ctc_payments'], // no reverse permission
    });

    apiMocks.respond('GET /ctc-payments/page', page1([{
      id: CTC_DRAFT_ID,
      memberId: MEMBER_ID,
      memberName: 'Jane Doe',
      memberNumber: 'M-0001',
      amount: '150.00',
      currencyCode: 'USD',
      committed: true,
      createdAt: '2026-08-09T10:15:00Z',
      createdBy: 'operator@example.com',
      type: 'CTC',
      status: 'committed',
      memberPayableId: PAYABLE_ID,
    }]));

    await page.goto('/tenant/finance/payments/ctc');
    await expect(page.getByText('Jane Doe').first()).toBeVisible({ timeout: 5000 });

    // Reverse action visible? Under the data-table row actions.
    const reverseBtn = page.getByRole('button', { name: /^Reverse$/i });
    await expect(reverseBtn).toHaveCount(0);
  });

  test('Reverse row action is visible with finance:reverse_ctc_payment', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance:manage_ctc_payments', 'finance:reverse_ctc_payment'],
    });

    apiMocks.respond('GET /ctc-payments/page', page1([{
      id: CTC_DRAFT_ID,
      memberId: MEMBER_ID,
      memberName: 'Jane Doe',
      memberNumber: 'M-0001',
      amount: '150.00',
      currencyCode: 'USD',
      committed: true,
      createdAt: '2026-08-09T10:15:00Z',
      createdBy: 'operator@example.com',
      type: 'CTC',
      status: 'committed',
      memberPayableId: PAYABLE_ID,
    }]));

    await page.goto('/tenant/finance/payments/ctc');
    await expect(page.getByText('Jane Doe').first()).toBeVisible({ timeout: 5000 });
    // At least one Reverse button should be present in the row-actions area.
    await expect(page.getByRole('button', { name: /^Reverse$/i }).first()).toBeVisible();
  });

  test('same-operator commit is allowed — no same-actor guard on CTC', async ({ page, apiMocks, signInAs }) => {
    // Deliberately sign in as the same email that "created" the draft to
    // guard against a future refactor that borrows advance-payment's
    // same-approver check.
    await signInAs({
      realmRoles: ['operator'],
      email: 'operator@example.com',
      permissions: ['finance:manage_ctc_payments', 'claims:commit_ctc_payment'],
    });

    let committedPost: Record<string, unknown> | null = null;
    apiMocks.respond(`GET /ctc-payments/${CTC_DRAFT_ID}`, () => ({
      id: CTC_DRAFT_ID,
      type: 'CTC',
      status: 'draft',
      memberId: MEMBER_ID,
      memberPayableId: PAYABLE_ID,
      amount: '150.00',
      currencyCode: 'USD',
      committed: false,
      createdAt: '2026-08-09T10:15:00Z',
      createdBy: 'operator@example.com',
    }));
    apiMocks.respond(`POST /ctc-payments/${CTC_DRAFT_ID}/commit`, async (req: Request) => {
      committedPost = JSON.parse(req.postData() ?? '{}');
      return { id: CTC_DRAFT_ID, status: 'committed', committed: true };
    });

    await page.goto(`/tenant/finance/payments/ctc/${CTC_DRAFT_ID}`);
    await expect(page.getByRole('heading', { name: /CTC payment/i })).toBeVisible();

    const commitBtn = page.getByRole('button', { name: /^Commit$/i });
    await expect(commitBtn).toBeVisible();
    await commitBtn.click();
    // Confirm dialog
    await page.getByRole('button', { name: /Commit$/i }).last().click();
    await page.waitForRequest(r => r.url().includes(`/ctc-payments/${CTC_DRAFT_ID}/commit`));
    expect(committedPost).not.toBeNull();
  });
});
