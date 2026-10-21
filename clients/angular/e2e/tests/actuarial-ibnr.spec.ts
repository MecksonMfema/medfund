import { test, expect } from '../fixtures/test';

/**
 * Phase 14 §Actuarial Phase 10 — IBNR triangle report page. Golden path:
 * filter row → submit → polling UI → split-view renders → export button.
 * Every API call is stubbed via ApiMocks so no live infra is required.
 */

const IBNR_JOB_ID = 'ibnr-123';

test.describe('Actuarial IBNR triangle (Phase 14 §Actuarial Phase 10)', () => {
  test('submit → poll → render split view → export enabled', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['finance:view', 'finance:view_subledger'],
    });

    // Toggle: first status call returns processing, second returns completed.
    let statusCalls = 0;
    apiMocks.respond('POST /reports/actuarial/ibnr', () => ({
      jobId: IBNR_JOB_ID, status: 'requested', deduplicated: false,
    }), 201);
    apiMocks.respond(`GET /reports/actuarial/jobs/${IBNR_JOB_ID}`, () => {
      statusCalls++;
      if (statusCalls === 1) {
        return {
          jobId: IBNR_JOB_ID, reportKey: 'IBNR_TRIANGLE',
          status: 'processing', progressPct: 50,
          paramsJson: null, resultJson: null, errorMessage: null,
          requestedAt: '2026-08-27T00:00:00Z', completedAt: null,
        };
      }
      return {
        jobId: IBNR_JOB_ID, reportKey: 'IBNR_TRIANGLE',
        status: 'completed', progressPct: 100,
        paramsJson: {
          periodStart: '2024-01-01', periodEnd: '2024-12-31',
          insuranceLine: 'HEALTH', shape: 'paid', grain: 'quarter',
          reportingCurrency: 'USD', ldfMethod: 'volume',
          triangle: {
            accident_periods: ['2024Q1', '2024Q2'],
            development_periods: ['1', '2'],
            cells: [[100, 150], [120, null]],
            grain: 'quarter', reporting_currency: 'USD', insurance_line: 'HEALTH',
          },
        },
        resultJson: {
          ldfs: [1.35, 1.10], cdf: [1.485, 1.10],
          ibnr_total: 42000, ultimate_total: 300000,
          mack_standard_error: 1500,
          per_cohort_ultimate: [180000, 120000],
        },
        errorMessage: null,
        requestedAt: '2026-08-27T00:00:00Z',
        completedAt: '2026-08-27T00:00:05Z',
      };
    });

    await page.goto('/tenant/finance/reports/actuarial/ibnr-triangle');
    await expect(page.getByRole('heading', { name: 'IBNR triangle' })).toBeVisible();

    // Submit the job.
    await page.getByRole('button', { name: /^Run report$/ }).click();

    // The split view lands only after the second status poll returns completed.
    await expect(page.locator('[data-testid="triangle-matrix"]'))
      .toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('IBNR total')).toBeVisible();
    await expect(page.getByText('42,000.00')).toBeVisible();

    // Export button lights up once the job is completed.
    await expect(page.getByRole('button', { name: 'Export XLSX' })).toBeEnabled();
  });
});
