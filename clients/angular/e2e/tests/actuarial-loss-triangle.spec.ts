import { test, expect } from '../fixtures/test';

/**
 * Phase 14 §Actuarial Phase 10 — LOSS_TRIANGLE report page. Same
 * pipeline as IBNR — the only differences are the submit endpoint
 * and the page label. Spec asserts the correct endpoint fires on submit.
 */

const LOSS_JOB_ID = 'loss-999';

test.describe('Actuarial LOSS triangle (Phase 14 §Actuarial Phase 10)', () => {
  test('submit hits /loss-triangle and renders once completed', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['finance:view', 'finance:view_subledger'],
    });

    let lossSubmitCalled = false;
    apiMocks.respond('POST /reports/actuarial/loss-triangle', () => {
      lossSubmitCalled = true;
      return { jobId: LOSS_JOB_ID, status: 'requested', deduplicated: false };
    }, 201);
    apiMocks.respond(`GET /reports/actuarial/jobs/${LOSS_JOB_ID}`, () => ({
      jobId: LOSS_JOB_ID, reportKey: 'LOSS_TRIANGLE',
      status: 'completed', progressPct: 100,
      paramsJson: {
        periodStart: '2024-01-01', periodEnd: '2024-12-31',
        insuranceLine: 'HEALTH', shape: 'paid', grain: 'quarter',
        reportingCurrency: 'USD', ldfMethod: 'volume',
        triangle: {
          accident_periods: ['2024Q1'], development_periods: ['1'],
          cells: [[200]],
          grain: 'quarter', reporting_currency: 'USD', insurance_line: 'HEALTH',
        },
      },
      resultJson: {
        ldfs: [1.2], cdf: [1.2], ibnr_total: 8_000, ultimate_total: 240_000,
        mack_standard_error: null, per_cohort_ultimate: [240_000],
      },
      errorMessage: null,
      requestedAt: '2026-08-27T00:00:00Z',
      completedAt: '2026-08-27T00:00:05Z',
    }));

    await page.goto('/tenant/finance/reports/actuarial/loss-triangle');
    await expect(page.getByRole('heading', { name: 'Loss triangle' })).toBeVisible();
    await page.getByRole('button', { name: /^Run report$/ }).click();

    await expect(page.locator('[data-testid="triangle-matrix"]')).toBeVisible({ timeout: 10_000 });
    expect(lossSubmitCalled).toBe(true);
  });
});
