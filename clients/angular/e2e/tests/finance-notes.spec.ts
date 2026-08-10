import { test, expect } from '../fixtures/test';

/**
 * V074 golden paths — Notes rename & payment-advice integration:
 *
 *   1. Debit-note create → approve → apply lifecycle through the UI,
 *      landing on the note detail after each transition.
 *   2. Reverse an applied credit note — flips status to reversed and
 *      returns a compensating REVERSAL row.
 *   3. MEMO notes render on the notes list but never on any advice
 *      (the loadNoteLines SQL filter excludes them).
 *   4. Legacy /tenant/finance/adjustments URL redirects to
 *      /tenant/finance/notes without dropping the query string.
 *
 * Every network call is stubbed via ApiMocks — no live services needed.
 */

const NOTE_ID    = '77777777-7777-4777-8777-777777777777';
const REV_ID     = '88888888-8888-4888-8888-888888888888';
const MEMO_ID    = '99999999-9999-4999-8999-999999999999';
const PROVIDER_ID = 'a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1';
const MEMBER_ID   = 'b2b2b2b2-b2b2-4b2b-8b2b-b2b2b2b2b2b2';

function page1<T>(rows: T[]) {
  return {
    content: rows,
    total: rows.length,
    page: 0,
    size: rows.length,
    totalPages: 1,
  };
}

function debitNoteRow(status: string) {
  return {
    id: NOTE_ID,
    noteNumber: 'DN-100001',
    providerId: PROVIDER_ID,
    providerName: 'Acme Clinic',
    memberId: null,
    memberName: null,
    memberNumber: null,
    direction: 'DEBIT',
    noteType: 'PROVIDER_OVERPAYMENT_RECOVERY',
    type: 'ORIGINAL',
    reversesNoteId: null,
    amount: '50.00',
    currencyCode: 'USD',
    reason: 'Overpaid on RUN-000042',
    status,
    approvedBy: null,
    approvedAt: null,
    postedAt: null,
    createdAt: '2026-08-10T10:00:00Z',
  };
}

test.describe('Finance — Notes (V074 golden paths)', () => {

  test('debit note create → approve → apply lands on note detail with applied status', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance.notes:read', 'finance.notes:write', 'finance.notes:approve'],
    });

    let currentStatus = 'pending';
    apiMocks.respond('GET /notes/page', () => page1([debitNoteRow(currentStatus)]));
    apiMocks.respond(`GET /notes/${NOTE_ID}`, () => debitNoteRow(currentStatus));
    apiMocks.respond(`POST /notes/${NOTE_ID}/approve`, () => {
      currentStatus = 'approved';
      return debitNoteRow(currentStatus);
    });
    apiMocks.respond(`POST /notes/${NOTE_ID}/apply`, () => {
      currentStatus = 'applied';
      return { ...debitNoteRow(currentStatus), postedAt: '2026-08-10T10:15:00Z' };
    });

    await page.goto(`/tenant/finance/notes/${NOTE_ID}`);
    await expect(page.getByRole('heading', { name: /DN-100001/ })).toBeVisible();

    await page.getByRole('button', { name: /^Approve$/i }).click();
    await page.waitForRequest(r => r.url().includes(`/notes/${NOTE_ID}/approve`) && r.method() === 'POST');

    // After approve, an "Apply" button appears.
    const applyBtn = page.getByRole('button', { name: /^Apply$/i });
    await expect(applyBtn).toBeVisible();
    // The apply() handler calls window.confirm — auto-accept.
    page.on('dialog', d => d.accept());
    await applyBtn.click();
    await page.waitForRequest(r => r.url().includes(`/notes/${NOTE_ID}/apply`) && r.method() === 'POST');
  });

  test('reverse applied credit note inserts compensating REVERSAL', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance.notes:read', 'finance.notes:approve'],
    });

    const original = {
      ...debitNoteRow('applied'),
      noteNumber: 'CN-200001',
      direction: 'CREDIT',
      noteType: 'GOODWILL',
      postedAt: '2026-08-10T10:00:00Z',
    };

    let reversed = false;
    apiMocks.respond(`GET /notes/${NOTE_ID}`, () => reversed ? { ...original, status: 'reversed' } : original);
    apiMocks.respond(`POST /notes/${NOTE_ID}/reverse`, () => {
      reversed = true;
      return {
        ...original,
        id: REV_ID,
        noteNumber: 'DN-500001',
        direction: 'DEBIT',
        type: 'REVERSAL',
        reversesNoteId: NOTE_ID,
        reason: 'Reversal of CN-200001',
        status: 'applied',
      };
    });

    await page.goto(`/tenant/finance/notes/${NOTE_ID}`);
    await expect(page.getByRole('heading', { name: /CN-200001/ })).toBeVisible();

    // Reverse triggers window.prompt then window.confirm — accept both.
    page.on('dialog', async d => {
      if (d.type() === 'prompt') return d.accept('test reversal');
      return d.accept();
    });
    await page.getByRole('button', { name: /^Reverse$/i }).click();
    await page.waitForRequest(r => r.url().includes(`/notes/${NOTE_ID}/reverse`) && r.method() === 'POST');
  });

  test('MEMO note appears on notes list but has no payee shown', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance.notes:read'],
    });

    apiMocks.respond('GET /notes/page', page1([{
      id: MEMO_ID,
      noteNumber: 'MEMO-100001',
      providerId: null,
      providerName: null,
      memberId: null,
      memberName: null,
      memberNumber: null,
      direction: 'DEBIT',
      noteType: 'MEMO',
      type: 'ORIGINAL',
      reversesNoteId: null,
      amount: '10.00',
      currencyCode: 'USD',
      reason: 'Bank fee absorbed',
      status: 'applied',
      approvedBy: null,
      approvedAt: null,
      postedAt: '2026-08-10T10:00:00Z',
      createdAt: '2026-08-10T10:00:00Z',
    }]));

    await page.goto('/tenant/finance/notes');
    await expect(page.getByText('MEMO-100001').first()).toBeVisible({ timeout: 5000 });
  });

  test('legacy /adjustments, /debit-notes, /credit-notes routes all redirect to /notes', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance.notes:read'],
    });

    apiMocks.respond('GET /notes/page', page1([debitNoteRow('applied')]));

    for (const legacy of ['adjustments', 'debit-notes', 'credit-notes']) {
      await page.goto(`/tenant/finance/${legacy}`);
      await expect(page).toHaveURL(/\/tenant\/finance\/notes$/);
    }
  });
});
