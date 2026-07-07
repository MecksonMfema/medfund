import type { Request } from '@playwright/test';
import { test, expect } from '../fixtures/test';
import { stubClaimsAPIs, emptyClaimsSeed } from '../fixtures/claims-stubs';
import { stubBillingAPIs, emptySeed as emptyBillingSeed } from '../fixtures/billing-stubs';

/**
 * Create a tariff schedule and confirm the effective-date / end-date
 * blur handlers snap to the correct cycle boundaries per
 * feedback_effective_date_snap. Also verifies the redirect into the
 * codes sub-page and that adding a code round-trips.
 */
test.describe('Claims — tariff schedules', () => {
  test('create schedule with mid-month picks snaps to 1st / last day', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['claims:view', 'claims:manage_tariffs'],
    });

    // Layout + tenant infrastructure share the billing surface — install
    // both harnesses so any bootstrap-time GET (schemes, currencies,
    // members search, etc.) doesn't 404 into the dashboard blank-page path.
    stubBillingAPIs(apiMocks, emptyBillingSeed());
    const seed = emptyClaimsSeed();
    stubClaimsAPIs(apiMocks, seed);

    let postedSchedule: Record<string, unknown> | null = null;
    apiMocks.respond('POST /tariffs/schedules', async (req: Request) => {
      postedSchedule = JSON.parse(req.postData() ?? '{}');
      const s = {
        id: 'tsc-e2e-1',
        name: (postedSchedule as any).name,
        effectiveDate: (postedSchedule as any).effectiveDate,
        endDate: (postedSchedule as any).endDate,
        source: (postedSchedule as any).source,
        status: 'ACTIVE',
        createdAt: new Date().toISOString(),
      };
      seed.tariffSchedules.push(s as any);
      return s;
    });

    await page.goto('/tenant/claims/tariffs/add');
    await expect(page.getByRole('heading', { name: /New tariff schedule/i })).toBeVisible({ timeout: 10_000 });

    await page.locator('input[name="name"]').fill('2026 AHFOZ Schedule');
    // Mid-month picks — the blur handlers snap: effectiveDate → 1st,
    // endDate → last day of the chosen month.
    await page.locator('input[name="effectiveDate"]').fill('2026-08-15');
    await page.locator('input[name="effectiveDate"]').blur();
    await page.locator('input[name="endDate"]').fill('2026-12-15');
    await page.locator('input[name="endDate"]').blur();

    await page.getByRole('button', { name: /create schedule/i }).click();
    await page.waitForURL('**/tenant/claims/tariffs/**/codes');

    expect(postedSchedule).toMatchObject({
      name: '2026 AHFOZ Schedule',
      effectiveDate: '2026-08-01',
      endDate: '2026-12-31',
    });
  });

  test('adding a tariff code round-trips into the codes list', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['claims:view', 'claims:manage_tariffs'],
    });

    stubBillingAPIs(apiMocks, emptyBillingSeed());
    const seed = emptyClaimsSeed();
    seed.tariffSchedules.push({
      id: 'tsc-1',
      name: 'AHFOZ Baseline',
      effectiveDate: '2026-01-01',
      endDate: '2026-12-31',
      status: 'ACTIVE',
    });
    stubClaimsAPIs(apiMocks, seed);

    let postedCode: Record<string, unknown> | null = null;
    apiMocks.respond('POST /tariffs/codes', async (req: Request) => {
      postedCode = JSON.parse(req.postData() ?? '{}');
      const c = {
        id: 'tc-e2e-1',
        scheduleId: (postedCode as any).scheduleId,
        code: (postedCode as any).code,
        description: (postedCode as any).description,
        category: (postedCode as any).category,
        unitPrice: String((postedCode as any).unitPrice),
        currencyCode: (postedCode as any).currencyCode,
        requiresPreAuth: (postedCode as any).requiresPreAuth ?? false,
      };
      seed.tariffCodes.push(c as any);
      return c;
    });

    await page.goto('/tenant/claims/tariffs/tsc-1/codes');
    // Heading pulls the schedule name.
    await expect(page.getByRole('heading', { name: /AHFOZ Baseline/i })).toBeVisible({ timeout: 10_000 });

    // Toggle the inline add form — the header button and the form submit
    // both read "Add code". The header one is a plain <button type="button">.
    await page.locator('.header-actions button', { hasText: /add code/i }).click();

    await page.locator('input[name="code"]').fill('0001A');
    await page.locator('input[name="description"]').fill('Consultation, first visit');
    await page.locator('input[name="unitPrice"]').fill('45.00');

    // Submit the form via the type=submit button inside .form-actions.
    await page.locator('form button[type="submit"]').click();

    await expect.poll(() => postedCode, { timeout: 10_000 }).not.toBeNull();
    expect(postedCode).toMatchObject({
      scheduleId: 'tsc-1',
      code: '0001A',
      description: 'Consultation, first visit',
    });
    // Row now surfaces in the list.
    await expect(page.locator('tr', { hasText: '0001A' })).toBeVisible({ timeout: 5000 });
  });
});
