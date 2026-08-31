import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 27 REG8 e2e coverage for the AML/STR alert queue + workflow
 * (Phase 23 UI + Phase 22 backend + Phase 26 per-STR auto-store).
 *
 * <p>Covers the golden RAISED → REVIEWED → FILED path — the queue lights
 * up per-row action buttons based on status + permissions, each modal
 * submits the matching PUT/POST, and the row status updates on reload.
 */

interface AlertRow {
  id: string;
  status: 'RAISED' | 'REVIEWED' | 'FILED' | 'CLOSED';
  transactionRef: string;
  transactionType: string;
  amountNative: string;
  currency: string;
  memberId: string | null;
  providerId: string | null;
  description: string;
  raisedByActorId: string | null;
  raisedByActorEmail: string | null;
  raisedAt: string;
  reviewerActorId: string | null;
  reviewerActorEmail: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  filerActorId: string | null;
  filerActorEmail: string | null;
  filedAt: string | null;
  filedRef: string | null;
  filedXlsxRef: string | null;
  closerActorId: string | null;
  closerActorEmail: string | null;
  closedAt: string | null;
  closedReason: string | null;
}

function raisedRow(): AlertRow {
  return {
    id: 'alert-1',
    status: 'RAISED',
    transactionRef: 'TXN-2026-000042',
    transactionType: 'PREMIUM',
    amountNative: '125000.00',
    currency: 'ZAR',
    memberId: null,
    providerId: null,
    description: 'Large round-number premium via cash on newly onboarded member; warrants review.',
    raisedByActorId: null,
    raisedByActorEmail: 'raiser@medfund',
    raisedAt: '2026-08-01T09:00:00Z',
    reviewerActorId: null,
    reviewerActorEmail: null,
    reviewedAt: null,
    reviewNote: null,
    filerActorId: null,
    filerActorEmail: null,
    filedAt: null,
    filedRef: null,
    filedXlsxRef: null,
    closerActorId: null,
    closerActorEmail: null,
    closedAt: null,
    closedReason: null,
  };
}

test.describe('AML/STR alert workflow (Phase 27 REG8)', () => {
  test('queue → Review → File: rows flip status + workflow modals submit the right PUTs',
      async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: [
        'compliance:aml_raise',
        'compliance:aml_review',
        'compliance:aml_file',
        'compliance:aml_close',
      ],
    });

    // Stateful in-memory row — the PUT handlers mutate it so the reload
    // reflects the new status like a real backend would.
    const row = raisedRow();

    apiMocks.respond('GET /regulatory/aml/alerts', () => ({
      content: [row],
      total: 1,
      page: 0,
      size: 50,
      totalPages: 1,
    }));

    apiMocks.respond('PUT /regulatory/aml/alerts/alert-1/review', async (req) => {
      const body = JSON.parse(req.postData() ?? '{}') as { reviewNote?: string };
      row.status = 'REVIEWED';
      row.reviewerActorEmail = 'operator@example.com';
      row.reviewedAt = '2026-08-02T10:00:00Z';
      row.reviewNote = body.reviewNote ?? '';
      return row;
    });

    apiMocks.respond('PUT /regulatory/aml/alerts/alert-1/file', async (req) => {
      const body = JSON.parse(req.postData() ?? '{}') as { filedRef?: string };
      row.status = 'FILED';
      row.filerActorEmail = 'operator@example.com';
      row.filedAt = '2026-08-03T14:30:00Z';
      row.filedRef = body.filedRef ?? '';
      row.filedXlsxRef = 's3://medfund-aml-filings/aml/str-filings/T/A/20260803-143000.xlsx';
      return row;
    });

    await page.goto('/tenant/finance/reports/compliance/aml-str/alerts');
    await expect(page.getByRole('heading', { name: /^AML\/STR alerts$/i })).toBeVisible();

    // Queue shows the RAISED row + a Review button, no File yet.
    await expect(page.locator('.mono', { hasText: 'TXN-2026-000042' })).toBeVisible();
    const reviewBtn = page.getByRole('button', { name: /^Review$/ });
    await expect(reviewBtn).toBeVisible();
    await expect(page.getByRole('button', { name: /^File$/ })).toHaveCount(0);

    // Open Review modal → fill note → submit → wait for PUT + reload.
    await reviewBtn.click();
    await expect(page.getByRole('dialog', { name: /^Review alert$/ })).toBeVisible();
    await page.getByLabel(/Review note/).fill('Triaged — matches Rand-round-number pattern.');

    const reviewReq = page.waitForRequest(
      r => r.url().includes('/regulatory/aml/alerts/alert-1/review') && r.method() === 'PUT',
    );
    await page.getByRole('button', { name: /^Confirm review$/ }).click();
    await reviewReq;

    // After the reload the row is REVIEWED and the File button appears.
    await expect(page.locator('.status-reviewed', { hasText: 'REVIEWED' })).toBeVisible();
    const fileBtn = page.getByRole('button', { name: /^File$/ });
    await expect(fileBtn).toBeVisible();

    // File flow → the request carries the regulator ref; auto-store fills
    // filedXlsxRef server-side so the "optional" input can stay blank.
    await fileBtn.click();
    await expect(page.getByRole('dialog', { name: /^File alert with regulator$/ })).toBeVisible();
    await page.getByLabel(/Regulator filing reference/).fill('FIU-STR-2026-0001234');

    const fileReq = page.waitForRequest(
      r => r.url().includes('/regulatory/aml/alerts/alert-1/file') && r.method() === 'PUT',
    );
    await page.getByRole('button', { name: /^Confirm filing$/ }).click();
    const filed = await fileReq;
    const filedBody = JSON.parse(filed.postData() ?? '{}');
    expect(filedBody.filedRef).toBe('FIU-STR-2026-0001234');

    await expect(page.locator('.status-filed', { hasText: 'FILED' })).toBeVisible();
    // Terminal state — Review/File/Close buttons disappear for FILED rows.
    await expect(page.getByRole('button', { name: /^Review$/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^File$/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Close$/ })).toHaveCount(0);
  });

  test('permission-gated: reviewer-only user sees Review but no File / Close / Raise buttons',
      async ({ page, apiMocks, signInAs }) => {
    // Only compliance:aml_review — no raise/file/close.
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['compliance:aml_review'],
    });

    apiMocks.respond('GET /regulatory/aml/alerts', () => ({
      content: [raisedRow()],
      total: 1,
      page: 0,
      size: 50,
      totalPages: 1,
    }));

    await page.goto('/tenant/finance/reports/compliance/aml-str/alerts');
    await expect(page.locator('.mono', { hasText: 'TXN-2026-000042' })).toBeVisible();

    await expect(page.getByRole('button', { name: /^Review$/ })).toBeVisible();
    // Missing permissions hide the header CTA + row-level buttons.
    await expect(page.getByRole('button', { name: /^Raise alert$/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Close$/ })).toHaveCount(0);
  });
});
