import type { Request } from '@playwright/test';
import { test, expect } from '../fixtures/test';
import { stubClaimsAPIs, emptyClaimsSeed, PreAuthorization, Provider } from '../fixtures/claims-stubs';
import { stubBillingAPIs, emptySeed as emptyBillingSeed } from '../fixtures/billing-stubs';

/**
 * Pre-auth lifecycle: request → approve (with end-of-month expiry snap
 * per feedback_effective_date_snap) → verify the reject-path too.
 */
test.describe('Claims — pre-authorizations', () => {
  test('operator submits a request, then approves with EOM-snapped expiry', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['claims:view', 'claims:manage_preauth'],
    });

    // Pre-auth form loads schemes + tenant currencies via the shared
    // services — reuse the billing stubs for those.
    const billingSeed = emptyBillingSeed();
    billingSeed.schemes.push({
      id: 'sch-1', name: 'Gold', schemeType: 'medical_aid', status: 'active',
      effectiveDate: '2025-01-01', endDate: null, currencyCode: 'USD',
      description: null, insuranceLine: 'HEALTH',
    });
    billingSeed.members.push({
      id: 'mem-1', memberNumber: 'M000001',
      firstName: 'Ada', lastName: 'Lovelace',
      dateOfBirth: '1990-01-01', email: 'ada@example.com', phone: '',
      status: 'ACTIVE', groupId: null, schemeId: 'sch-1',
      enrollmentDate: '2025-01-01', createdAt: '2025-01-01T00:00:00Z',
    });
    stubBillingAPIs(apiMocks, billingSeed);

    const claimsSeed = emptyClaimsSeed();
    const provider: Provider = {
      id: 'prv-1', name: 'City Hospital',
      specialty: 'General', registrationNumber: 'AHFOZ-001', status: 'ACTIVE',
    };
    claimsSeed.providers.push(provider);
    stubClaimsAPIs(apiMocks, claimsSeed);

    let postedRequest: Record<string, unknown> | null = null;
    apiMocks.respond('POST /pre-authorizations', async (req: Request) => {
      postedRequest = JSON.parse(req.postData() ?? '{}');
      const p: PreAuthorization = {
        id: 'pa-e2e-1',
        authNumber: 'PA-000001',
        memberId: (postedRequest as any).memberId,
        providerId: (postedRequest as any).providerId,
        schemeId: (postedRequest as any).schemeId,
        tariffCode: (postedRequest as any).tariffCode,
        diagnosisCode: (postedRequest as any).diagnosisCode,
        status: 'pending',
        requestedAmount: String((postedRequest as any).requestedAmount),
        currencyCode: (postedRequest as any).currencyCode,
        requestedDate: new Date().toISOString().slice(0, 10),
        notes: (postedRequest as any).notes,
        createdAt: new Date().toISOString(),
      };
      claimsSeed.preAuths.push(p);
      return p;
    });

    await page.goto('/tenant/claims/preauth/new');
    await expect(page.getByRole('heading', { name: /New pre-authorization request/i })).toBeVisible();

    // Member typeahead → click Ada.
    const memberInput = page.locator('input[name="memberQuery"]');
    await memberInput.click();
    const memberResp = page.waitForResponse(r => r.url().includes('/members/search') && r.status() === 200);
    await memberInput.pressSequentially('Ada', { delay: 60 });
    await memberResp;
    await page.locator('.suggestions .suggestion', { hasText: 'Ada' }).first().click();

    // Provider typeahead → click City Hospital.
    const providerInput = page.locator('input[name="providerQuery"]');
    await providerInput.click();
    const providerResp = page.waitForResponse(r => r.url().includes('/providers') && r.status() === 200);
    await providerInput.pressSequentially('City', { delay: 60 });
    await providerResp;
    await page.locator('.suggestions .suggestion', { hasText: 'City Hospital' }).first().click();

    // Scheme + currency via app-select.
    await page.locator('app-select[name="schemeId"] .select-trigger').click();
    await page.locator('.select-option .option-label', { hasText: /^Gold/ }).first().click();
    await page.locator('app-select[name="currencyCode"] .select-trigger').click();
    await page.locator('.select-option .option-label', { hasText: /^USD$/ }).first().click();

    // Remaining fields.
    await page.locator('input[name="tariffCode"]').fill('0001A');
    await page.locator('input[name="diagnosisCode"]').fill('C50.9');
    await page.locator('input[name="requestedAmount"]').fill('500.00');

    await page.getByRole('button', { name: /submit request/i }).click();
    await page.waitForURL('**/tenant/claims/preauth/pa-e2e-1');

    // requestedAmount is coerced to number by Angular's [(ngModel)] on
    // the type="number" input (same pattern as billing-transaction).
    expect(postedRequest).toMatchObject({
      memberId: 'mem-1',
      providerId: 'prv-1',
      schemeId: 'sch-1',
      tariffCode: '0001A',
      requestedAmount: 500,
      currencyCode: 'USD',
    });

    // ── Approve — capture the URL params to assert the EOM expiry snap
    // pushed by pre-auth-detail's default (+90 days snapped to end of
    // that month) reaches the backend.
    let approveUrl = '';
    apiMocks.respond('POST /pre-authorizations/:id/approve', async (req: Request) => {
      approveUrl = req.url();
      const p = claimsSeed.preAuths[0];
      p.status = 'approved';
      p.approvedAmount = new URL(req.url()).searchParams.get('approvedAmount') ?? p.requestedAmount;
      p.expiryDate = new URL(req.url()).searchParams.get('expiryDate') ?? undefined;
      return p;
    });

    await expect(page.locator('h1', { hasText: /PA-000001/ })).toBeVisible({ timeout: 10_000 });

    // Fill approved amount + click Approve. The expiryDate is pre-seeded
    // at ngOnInit to endOfMonth(today+90d).
    await page.locator('input[name="approvedAmount"]').fill('450.00');
    // Confirmation dialog fires from the approve() method — accept.
    page.once('dialog', d => d.accept());
    await page.getByRole('button', { name: /^Approve$/ }).click();

    await expect.poll(() => approveUrl, { timeout: 10_000 }).not.toBe('');
    const parsed = new URL(approveUrl);
    // Numeric input strips trailing zeros when re-serialized to URL.
    expect(parsed.searchParams.get('approvedAmount')).toBe('450');
    // The default expiry is endOfMonth(now+90d). Compute expected +/- a
    // day to keep the test stable if `now` shifts at boundary.
    const t = new Date();
    t.setDate(t.getDate() + 90);
    const expectedEom = new Date(t.getFullYear(), t.getMonth() + 1, 0);
    const expected = `${expectedEom.getFullYear()}-${String(expectedEom.getMonth() + 1).padStart(2, '0')}-${String(expectedEom.getDate()).padStart(2, '0')}`;
    expect(parsed.searchParams.get('expiryDate')).toBe(expected);
  });

  test('reject flow forwards the rejection reason', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['claims:view', 'claims:manage_preauth'],
    });

    stubBillingAPIs(apiMocks, emptyBillingSeed());
    const claimsSeed = emptyClaimsSeed();
    claimsSeed.preAuths.push({
      id: 'pa-e2e-2',
      authNumber: 'PA-000002',
      memberId: 'mem-1', providerId: 'prv-1', schemeId: 'sch-1',
      tariffCode: '0002B', status: 'pending',
      requestedAmount: '300.00', currencyCode: 'USD',
      requestedDate: '2026-07-01',
      createdAt: new Date().toISOString(),
    });
    stubClaimsAPIs(apiMocks, claimsSeed);

    let rejectUrl = '';
    apiMocks.respond('POST /pre-authorizations/:id/reject', async (req: Request) => {
      rejectUrl = req.url();
      const p = claimsSeed.preAuths[0];
      p.status = 'rejected';
      p.rejectionReason = new URL(req.url()).searchParams.get('rejectionReason') ?? undefined;
      return p;
    });

    await page.goto('/tenant/claims/preauth/pa-e2e-2');
    // Two headings match — h1 "PA-000002" and h2 "Pre-authorization PA-000002".
    // Scope to the h1 (auth number) since that's the canonical page anchor.
    await expect(page.locator('h1', { hasText: /PA-000002/ })).toBeVisible({ timeout: 10_000 });

    await page.locator('textarea[name="rejectReason"], input[name="rejectReason"]').first()
      .fill('Not covered under the current benefit');
    page.once('dialog', d => d.accept());
    await page.getByRole('button', { name: /^Reject$/ }).click();

    await expect.poll(() => rejectUrl, { timeout: 10_000 }).not.toBe('');
    expect(new URL(rejectUrl).searchParams.get('rejectionReason'))
      .toBe('Not covered under the current benefit');
  });
});
