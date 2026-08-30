import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { of, EMPTY } from 'rxjs';
import { InsuranceRevenueServiceResultComponent } from './insurance-revenue-service-result.component';
import { Ifrs17ReportsService } from '../../../../../core/services/ifrs17-reports.service';
import { ReportJobPollingService } from '../../../../../core/services/report-job-polling.service';
import { CurrencyService } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';

describe('InsuranceRevenueServiceResultComponent', () => {
  let cmp: InsuranceRevenueServiceResultComponent;
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
    const fixture = TestBed.createComponent(InsuranceRevenueServiceResultComponent);
    cmp = fixture.componentInstance;
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).verify();
  });

  it('submit posts to the insurance-revenue endpoint', () => {
    reports.submitInsuranceRevenueServiceResult.and.returnValue(of({
      jobId: 'rev-1', status: 'requested', chunkCount: 3, deduped: false,
    }));
    cmp.periodStart = '2026-01-01';
    cmp.periodEnd = '2026-06-30';
    cmp.submit();
    expect(reports.submitInsuranceRevenueServiceResult).toHaveBeenCalledTimes(1);
    expect(reports.submitLrcLicReconciliation).not.toHaveBeenCalled();
    expect(polling.poll).toHaveBeenCalledWith('rev-1');
  });

  it('portfolios getter sums revenue / expenses / service result / finance expense', () => {
    cmp['currentJob'] = {
      jobId: 'j', status: 'completed',
      resultJson: {
        summary: { totalChunks: 2, completedChunks: 2, failedChunks: 0,
                   measurementModelsSeen: ['PAA'], currenciesSeen: ['USD'] },
        portfolios: {
          'p-1': {
            cohorts: {
              'c-1': {
                'USD': { chunkId: 'x', status: 'completed', model: 'PAA', result: {
                  insuranceRevenue: 100, insuranceServiceExpenses: 70,
                  insuranceServiceResult: 30, insuranceFinanceExpense: 5,
                }},
              },
              'c-2': {
                'USD': { chunkId: 'y', status: 'completed', model: 'PAA', result: {
                  insuranceRevenue: 80, insuranceServiceExpenses: 50,
                  // no explicit serviceResult — derived from revenue - expenses.
                  insuranceFinanceExpense: 3,
                }},
              },
            },
          },
        },
      },
    } as any;
    const p = cmp.portfolios;
    expect(p.length).toBe(1);
    expect(p[0].totals.insuranceRevenue).toBe(180);
    expect(p[0].totals.insuranceServiceExpenses).toBe(120);
    // Explicit 30 + derived (80 - 50) = 60.
    expect(p[0].totals.insuranceServiceResult).toBe(60);
    expect(p[0].totals.insuranceFinanceExpense).toBe(8);
  });

  it('waterfallFor renders start-anchor → revenue → -expenses → -finance → net-anchor', () => {
    const bucket = {
      portfolioId: 'p-A',
      cohorts: [],
      totals: {
        insuranceRevenue: 100,
        insuranceServiceExpenses: 70,
        insuranceServiceResult: 30,
        insuranceFinanceExpense: 5,
      },
    };
    const w = cmp.waterfallFor(bucket);
    expect(w[0]).toEqual(jasmine.objectContaining({ name: 'Start', value: 0, isAnchor: true }));
    expect(w[1].value).toBe(100);
    expect(w[2].value).toBe(-70);
    expect(w[3].value).toBe(-5);
    expect(w[4]).toEqual(jasmine.objectContaining({ name: 'Net earnings', value: 25, isAnchor: true }));
  });
});
