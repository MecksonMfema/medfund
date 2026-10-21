import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 15 §21 — LRC / LIC reconciliation report page. Golden path:
 * open page → filter row → submit → polling UI → treetable renders +
 * waterfall shows → export XLSX opens in a new tab. Every API call is
 * stubbed via ApiMocks so the spec runs without live infrastructure.
 */

const JOB_ID = 'ifrs17-lrc-1';

test.describe('IFRS 17 LRC / LIC reconciliation (Phase 15 §21)', () => {
  test('submit → poll → render treetable + waterfall → export enabled', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['finance:view', 'finance:view_subledger'],
    });

    // The report toggle probe on the tenant-portal load pulls report-config;
    // return both IFRS 17 keys enabled so the page's report-key gate passes.
    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, [
      { id: null, tenantId: TENANT, reportKey: 'IFRS17_LRC_LIC_RECONCILIATION',
        label: 'IFRS 17 — LRC / LIC reconciliation', family: 'REGULATORY',
        familyLabel: 'Regulatory', enabled: true, cadenced: true,
        updatedAt: null, updatedBy: null },
      { id: null, tenantId: TENANT, reportKey: 'IFRS17_INSURANCE_REVENUE_SERVICE_RESULT',
        label: 'IFRS 17 — insurance revenue & service result', family: 'REGULATORY',
        familyLabel: 'Regulatory', enabled: true, cadenced: true,
        updatedAt: null, updatedBy: null },
    ]);

    // Two-stage polling: first status returns processing, next returns
    // completed with a portfolio-shaped envelope the treetable renders from.
    let statusCalls = 0;
    apiMocks.respond('POST /reports/ifrs17/lrc-lic-reconciliation', () => ({
      jobId: JOB_ID, status: 'requested', chunkCount: 1, deduped: false,
    }), 201);
    apiMocks.respond(`GET /reports/jobs/${JOB_ID}`, () => {
      statusCalls++;
      if (statusCalls === 1) {
        return {
          jobId: JOB_ID, reportKey: 'IFRS17_LRC_LIC_RECONCILIATION',
          status: 'processing', progressPct: 50,
          paramsJson: null, resultJson: null, errorMessage: null,
          requestedAt: '2026-08-30T00:00:00Z', completedAt: null,
        };
      }
      return {
        jobId: JOB_ID, reportKey: 'IFRS17_LRC_LIC_RECONCILIATION',
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
                      lrc: {
                        opening: 10000, newBusiness: 2000, cashInflows: 1500,
                        insuranceRevenue: 1800, financeExpense: 200, closing: 11900,
                      },
                      lic: {
                        opening: 5000, newBusiness: 0, cashOutflows: -700,
                        claimsIncurred: 900, financeExpense: 100, closing: 5300,
                      },
                    },
                  },
                },
              },
            },
          },
        },
        errorMessage: null,
        requestedAt: '2026-08-30T00:00:00Z',
        completedAt: '2026-08-30T00:00:05Z',
      };
    });

    await page.goto('/tenant/finance/reports/ifrs17/lrc-lic-reconciliation');
    await expect(page.getByRole('heading', { name: /LRC \/ LIC reconciliation/i })).toBeVisible();

    // Submit — the button label flips to Submitting… then back.
    await page.getByRole('button', { name: /^Run report$/ }).click();

    // Summary strip lands only when polling completes.
    await expect(page.locator('[data-testid="ifrs17-summary"]'))
      .toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('1 / 1', { exact: false })).toBeVisible();

    // At least one portfolio block, with both LRC + LIC tables (default view).
    const portfolio = page.locator('[data-testid="ifrs17-portfolio"]').first();
    await expect(portfolio).toBeVisible();
    await expect(portfolio.locator('[data-testid="ifrs17-lrc-table"]')).toBeVisible();
    await expect(portfolio.locator('[data-testid="ifrs17-lic-table"]')).toBeVisible();

    // Cohort row values render — pick one cell so a data-binding regression
    // fails loudly rather than the table shape being technically correct
    // but empty. The cohort id shows in both LRC and LIC tables, so scope
    // to the LRC table specifically.
    const lrcTable = portfolio.locator('[data-testid="ifrs17-lrc-table"]');
    await expect(lrcTable.getByText('MISC-2026-DEFAULT')).toBeVisible();
    await expect(lrcTable.getByText('10,000.00').first()).toBeVisible();

    // Movement waterfall stanza — the chart component renders inside the block.
    await expect(portfolio.getByText('Movement waterfall')).toBeVisible();

    // Export button is enabled once the job is completed.
    await expect(page.getByRole('button', { name: /^Export XLSX$/ })).toBeEnabled();

    // View toggle: switch to LRC-only, LIC table hides.
    await page.getByRole('tab', { name: /^LRC$/ }).click();
    await expect(portfolio.locator('[data-testid="ifrs17-lrc-table"]')).toBeVisible();
    await expect(portfolio.locator('[data-testid="ifrs17-lic-table"]')).toHaveCount(0);
  });
});
