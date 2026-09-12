import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import {
  FinanceService,
  GroupBillingSummaryRow,
  ReportResponse,
} from '../../../../../core/services/finance.service';
import { CurrencyService } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { GroupBillingReportComponent } from './group-billing-report.component';

/**
 * Focused on the per-holder rendering: mock the finance service to return
 * one GROUP row and one INDIVIDUAL row, then assert the data-table
 * renders the correct holder-type chips (regression guard for the
 * 2026-09 per-holder rewrite).
 */
describe('GroupBillingReportComponent', () => {
  function envelope(rows: GroupBillingSummaryRow[]): ReportResponse<GroupBillingSummaryRow[]> {
    return {
      reportKey: 'GROUP_BILLING_REPORT',
      period: { periodStart: '2026-07-01', periodEnd: '2026-07-31', grain: 'MONTHLY' },
      reportingCurrency: 'USD',
      data: rows,
      perCurrency: {},
      fxRates: {},
      warnings: [],
      generatedAt: '2026-08-01T00:00:00Z',
    } as ReportResponse<GroupBillingSummaryRow[]>;
  }

  function row(overrides: Partial<GroupBillingSummaryRow>): GroupBillingSummaryRow {
    return {
      groupId: '00000000-0000-0000-0000-000000000001',
      groupName: 'Acme Corp',
      holderType: 'GROUP',
      currencyCode: 'USD',
      principalCount: 5,
      dependantCount: 10,
      livesCovered: 15,
      totalBilled: '1000.00',
      totalPaid: '900.00',
      ...overrides,
    };
  }

  beforeEach(() => {
    const finance = jasmine.createSpyObj<FinanceService>('FinanceService',
      ['getGroupBillingReport', 'exportGroupBillingExcel']);
    finance.getGroupBillingReport.and.returnValue(of(envelope([
      row({}),
      row({
        groupId: '00000000-0000-0000-0000-000000000002',
        groupName: 'Jane Doe',
        holderType: 'INDIVIDUAL',
        principalCount: 1,
        dependantCount: 0,
        livesCovered: 1,
        totalBilled: '250.00',
        totalPaid: '250.00',
      }),
    ])));

    const currency = jasmine.createSpyObj<CurrencyService>('CurrencyService', ['listForTenant']);
    currency.listForTenant.and.returnValue(of([]));

    const tenant = jasmine.createSpyObj<TenantService>('TenantService', ['getTenantId']);
    tenant.getTenantId.and.returnValue('11111111-1111-1111-1111-111111111111');

    TestBed.configureTestingModule({
      imports: [GroupBillingReportComponent],
      providers: [
        provideRouter([]), provideNoopAnimations(),
        { provide: FinanceService,  useValue: finance  },
        { provide: CurrencyService, useValue: currency },
        { provide: TenantService,   useValue: tenant   },
      ],
    });
  });

  it('renders a holder-type chip for every row', () => {
    const fixture = TestBed.createComponent(GroupBillingReportComponent);
    fixture.detectChanges();

    const chips = fixture.nativeElement.querySelectorAll('.holder-chip') as NodeListOf<HTMLElement>;
    expect(chips.length).toBe(2);
    const labels = Array.from(chips).map(c => c.textContent!.trim());
    expect(labels).toContain('Group');
    expect(labels).toContain('Individual');
  });
});
