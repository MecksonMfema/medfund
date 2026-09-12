import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import {
  FinanceService,
  ReceiptsSummaryRow,
  ReportResponse,
} from '../../../../../core/services/finance.service';
import { CurrencyService } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { GroupReceiptsReportComponent } from './group-receipts-report.component';

/**
 * Regression guard for the 2026-09 per-holder rewrite of the per-group
 * receipts report — every row must carry a visible holder-type chip so
 * corporate groups and individual policyholders are distinguishable in
 * the UI.
 */
describe('GroupReceiptsReportComponent', () => {
  function envelope(rows: ReceiptsSummaryRow[]): ReportResponse<ReceiptsSummaryRow[]> {
    return {
      reportKey: 'RECEIPTS_REPORT',
      period: { periodStart: '2026-07-01', periodEnd: '2026-07-31', grain: 'MONTHLY' },
      reportingCurrency: 'USD',
      data: rows,
      perCurrency: {},
      fxRates: {},
      warnings: [],
      generatedAt: '2026-08-01T00:00:00Z',
    } as ReportResponse<ReceiptsSummaryRow[]>;
  }

  beforeEach(() => {
    const finance = jasmine.createSpyObj<FinanceService>('FinanceService',
      ['getGroupReceiptsReport', 'exportGroupReceiptsExcel']);
    finance.getGroupReceiptsReport.and.returnValue(of(envelope([
      {
        dimensionId: '00000000-0000-0000-0000-000000000001',
        dimensionName: 'Acme Corp',
        holderType: 'GROUP',
        insuranceLine: null,
        currencyCode: 'USD',
        totalReceived: '500.00',
        transactionCount: 3,
      },
      {
        dimensionId: '00000000-0000-0000-0000-000000000002',
        dimensionName: 'Jane Doe',
        holderType: 'INDIVIDUAL',
        insuranceLine: null,
        currencyCode: 'USD',
        totalReceived: '80.00',
        transactionCount: 1,
      },
    ])));

    const currency = jasmine.createSpyObj<CurrencyService>('CurrencyService', ['listForTenant']);
    currency.listForTenant.and.returnValue(of([]));

    const tenant = jasmine.createSpyObj<TenantService>('TenantService', ['getTenantId']);
    tenant.getTenantId.and.returnValue('11111111-1111-1111-1111-111111111111');

    TestBed.configureTestingModule({
      imports: [GroupReceiptsReportComponent],
      providers: [
        provideRouter([]), provideNoopAnimations(),
        { provide: FinanceService,  useValue: finance  },
        { provide: CurrencyService, useValue: currency },
        { provide: TenantService,   useValue: tenant   },
      ],
    });
  });

  it('renders a holder-type chip for every row', () => {
    const fixture = TestBed.createComponent(GroupReceiptsReportComponent);
    fixture.detectChanges();

    const chips = fixture.nativeElement.querySelectorAll('.holder-chip') as NodeListOf<HTMLElement>;
    expect(chips.length).toBe(2);
    const labels = Array.from(chips).map(c => c.textContent!.trim());
    expect(labels).toContain('Group');
    expect(labels).toContain('Individual');
  });
});
