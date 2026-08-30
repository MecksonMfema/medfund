import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { of, EMPTY } from 'rxjs';
import { LrcLicReconciliationComponent } from './lrc-lic-reconciliation.component';
import { Ifrs17ReportsService } from '../../../../../core/services/ifrs17-reports.service';
import { ReportJobPollingService } from '../../../../../core/services/report-job-polling.service';
import { CurrencyService } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';

describe('LrcLicReconciliationComponent', () => {
  let cmp: LrcLicReconciliationComponent;
  let reports: jasmine.SpyObj<Ifrs17ReportsService>;
  let polling: jasmine.SpyObj<ReportJobPollingService>;
  const tenantId = 'tnt-1';

  beforeEach(() => {
    reports = jasmine.createSpyObj<Ifrs17ReportsService>(
      'Ifrs17ReportsService',
      ['submitLrcLicReconciliation', 'submitInsuranceRevenueServiceResult', 'exportXlsxUrl']);
    polling = jasmine.createSpyObj<ReportJobPollingService>(
      'ReportJobPollingService', ['poll', 'status']);
    polling.poll.and.returnValue(EMPTY);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Ifrs17ReportsService, useValue: reports },
        { provide: ReportJobPollingService, useValue: polling },
        { provide: CurrencyService, useValue: { listForTenant: () => of([]) } },
        { provide: TenantService, useValue: { getTenantId: () => tenantId } },
      ],
    });
    const fixture = TestBed.createComponent(LrcLicReconciliationComponent);
    cmp = fixture.componentInstance;
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).verify();
  });

  it('submit posts the IFRS 17 request body and starts polling', () => {
    reports.submitLrcLicReconciliation.and.returnValue(of({
      jobId: 'job-1', status: 'requested', chunkCount: 2, deduped: false,
    }));

    cmp.periodStart = '2026-01-01';
    cmp.periodEnd = '2026-06-30';
    cmp.reportingCurrency = 'USD';
    cmp.submit();

    expect(reports.submitLrcLicReconciliation).toHaveBeenCalledWith(jasmine.objectContaining({
      periodStart: '2026-01-01',
      periodEnd: '2026-06-30',
      portfolioIds: null,
      reportingCurrency: 'USD',
    }));
    expect(polling.poll).toHaveBeenCalledWith('job-1');
    expect(cmp.submitting).toBeFalse();
  });

  it('validates period-start / period-end before submit', () => {
    cmp.periodStart = '';
    cmp.periodEnd = '';
    cmp.submit();
    expect(reports.submitLrcLicReconciliation).not.toHaveBeenCalled();
    expect(cmp.errorMessage).toContain('start');
  });

  it('portfolios getter flattens envelope into per-portfolio buckets with LRC + LIC totals', () => {
    cmp['currentJob'] = {
      jobId: 'j', status: 'completed',
      resultJson: {
        summary: { totalChunks: 2, completedChunks: 2, failedChunks: 0,
                   measurementModelsSeen: ['PAA'], currenciesSeen: ['USD'] },
        portfolios: {
          'p-A': {
            cohorts: {
              'c-1': {
                'USD': {
                  chunkId: 'ck-1', status: 'completed', model: 'PAA',
                  result: {
                    lrc: { opening: 100, newBusiness: 0, cashInflows: 50,
                           insuranceRevenue: 30, financeExpense: 2, closing: 122 },
                    lic: { opening: 10, newBusiness: 0, cashOutflows: -5,
                           claimsIncurred: 8, financeExpense: 0, closing: 13 },
                  },
                },
              },
              'c-2': {
                'USD': {
                  chunkId: 'ck-2', status: 'completed', model: 'PAA',
                  result: {
                    lrc: { opening: 200, newBusiness: 10, cashInflows: 80,
                           insuranceRevenue: 50, financeExpense: 3, closing: 243 },
                    lic: { opening: 20, newBusiness: 0, cashOutflows: -8,
                           claimsIncurred: 12, financeExpense: 0, closing: 24 },
                  },
                },
              },
            },
          },
        },
      },
    } as any;

    const portfolios = cmp.portfolios;
    expect(portfolios.length).toBe(1);
    expect(portfolios[0].portfolioId).toBe('p-A');
    expect(portfolios[0].cohorts.length).toBe(2);
    expect(portfolios[0].lrcTotals.opening).toBe(300);
    expect(portfolios[0].lrcTotals.closing).toBe(365);
    expect(portfolios[0].licTotals.opening).toBe(30);
    expect(portfolios[0].licTotals.closing).toBe(37);
  });

  it('view toggle controls LRC / LIC table visibility', () => {
    cmp.setView('LRC');
    expect(cmp.showLrc()).toBeTrue();
    expect(cmp.showLic()).toBeFalse();
    cmp.setView('LIC');
    expect(cmp.showLic()).toBeTrue();
    expect(cmp.showLrc()).toBeFalse();
    cmp.setView('COMBINED');
    expect(cmp.showLrc()).toBeTrue();
    expect(cmp.showLic()).toBeTrue();
  });

  it('exportXlsx opens the tenant-scoped XLSX URL for the current job', () => {
    const opener = spyOn(window, 'open');
    reports.exportXlsxUrl.and.returnValue('/api/v1/reports/ifrs17/jobs/job-9/export.xlsx');
    cmp['currentJob'] = { jobId: 'job-9', status: 'completed' } as any;
    cmp.exportXlsx();
    expect(reports.exportXlsxUrl).toHaveBeenCalledWith('job-9');
    expect(opener).toHaveBeenCalledWith('/api/v1/reports/ifrs17/jobs/job-9/export.xlsx', '_blank');
  });

  it('waterfallFor produces an opening → deltas → closing sequence with anchors flagged', () => {
    const bucket = {
      portfolioId: 'p-A',
      cohorts: [],
      lrcTotals: {
        opening: 100, newBusiness: 20, cashFlows: 30,
        revenueOrClaims: 40, financeExpense: 2, closing: 112,
      },
      licTotals: {
        opening: 10, newBusiness: 0, cashFlows: -5,
        revenueOrClaims: 8, financeExpense: 0, closing: 13,
      },
    };
    const w = cmp.waterfallFor(bucket);
    expect(w[0].isAnchor).toBeTrue();
    expect(w[0].value).toBe(110);
    expect(w[w.length - 1].isAnchor).toBeTrue();
    expect(w[w.length - 1].value).toBe(125);
    // Revenue / claims flips sign (release = drop in liability).
    const revenueStep = w.find(s => s.name === 'Revenue / claims');
    expect(revenueStep!.value).toBe(-48);
  });
});
