import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 15 §21 — insurance revenue & service result report page. Same
 * submit / poll / render / export shape as the sibling LRC / LIC report;
 * only the treetable columns differ (revenue / service expenses /
 * service result / finance expense).
 */

const JOB_ID = 'ifrs17-rev-1';

test.describe('IFRS 17 insurance revenue & service result (Phase 15 §21)', () => {
  test('submit → poll → render revenue table + waterfall → export enabled', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['finance:view', 'finance:view_subledger'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, [
      { id: null, tenantId: TENANT, reportKey: 'IFRS17_INSURANCE_REVENUE_SERVICE_RESULT',
        label: 'IFRS 17 — insurance revenue & service result', family: 'REGULATORY',
        familyLabel: 'Regulatory', enabled: true, cadenced: true,
        updatedAt: null, updatedBy: null },
    ]);

    let statusCalls = 0;
    apiMocks.respond('POST /reports/ifrs17/insurance-revenue-service-result', () => ({
      jobId: JOB_ID, status: 'requested', chunkCount: 1, deduped: false,
    }), 201);
    apiMocks.respond(`GET /reports/jobs/${JOB_ID}`, () => {
      statusCalls++;
      if (statusCalls === 1) {
        return {
          jobId: JOB_ID, reportKey: 'IFRS17_INSURANCE_REVENUE_SERVICE_RESULT',
          status: 'processing', progressPct: 40,
          paramsJson: null, resultJson: null, errorMessage: null,
          requestedAt: '2026-08-30T00:00:00Z', completedAt: null,
        };
      }
      return {
        jobId: JOB_ID, reportKey: 'IFRS17_INSURANCE_REVENUE_SERVICE_RESULT',
        status: 'completed', progressPct: 100,
        paramsJson: { periodStart: '2026-07-01', periodEnd: '2026-07-31' },
        resultJson: {
          summary: {
            totalChunks: 1, completedChunks: 1, failedChunks: 0,
            measurementModelsSeen: ['PAA'], currenciesSeen: ['USD'],
          },
          portfolios: {
            'portfolio-1': {
              cohorts: {
                'MISC-2026-DEFAULT': {
                  USD: {
                    chunkId: 'chunk-1',
                    status: 'completed',
                    model: 'PAA',
                    result: {
                      insuranceRevenue: 12000,
                      insuranceServiceExpenses: 8500,
                      insuranceServiceResult: 3500,
                      insuranceFinanceExpense: 250,
                    },
                  },
                },
              },
            },
          },
        },
        errorMessage: null,
        requestedAt: '2026-08-30T00:00:00Z',
        completedAt: '2026-08-30T00:00:04Z',
      };
    });

    await page.goto('/tenant/finance/reports/ifrs17/insurance-revenue-service-result');
    await expect(page.getByRole('heading', { name: /insurance revenue & service result/i })).toBeVisible();

    await page.getByRole('button', { name: /^Run report$/ }).click();

    await expect(page.locator('[data-testid="ifrs17-summary"]'))
      .toBeVisible({ timeout: 15_000 });

    const portfolio = page.locator('[data-testid="ifrs17-portfolio"]').first();
    await expect(portfolio.locator('[data-testid="ifrs17-revenue-table"]')).toBeVisible();
    // Cohort row values render.
    await expect(portfolio.getByText('MISC-2026-DEFAULT')).toBeVisible();
    await expect(portfolio.getByText('12,000.00').first()).toBeVisible();
    await expect(portfolio.getByText('3,500.00').first()).toBeVisible();

    // Export button lights up once the terminal poll returns.
    await expect(page.getByRole('button', { name: /^Export XLSX$/ })).toBeEnabled();
  });
});
