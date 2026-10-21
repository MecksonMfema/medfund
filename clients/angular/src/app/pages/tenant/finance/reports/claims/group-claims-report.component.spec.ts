import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import {
  ClaimsReportService,
  ClaimsSummaryRow,
} from '../../../../../core/services/claims-report.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { CurrencyService } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { GroupClaimsReportComponent } from './group-claims-report.component';

/**
 * Regression guard for the 2026-09 per-holder rewrite of the per-group
 * claims report — every row must carry a visible holder-type chip so
 * corporate groups and individual policyholders are distinguishable in
 * the UI.
 */
describe('GroupClaimsReportComponent', () => {
  function envelope(rows: ClaimsSummaryRow[]): ReportResponse<ClaimsSummaryRow[]> {
    return {
      reportKey: 'CLAIMS_SUMMARY',
      period: { periodStart: '2026-07-01', periodEnd: '2026-07-31', grain: 'MONTHLY' },
      reportingCurrency: 'USD',
      data: rows,
      perCurrency: {},
      fxRates: {},
      warnings: [],
      generatedAt: '2026-08-01T00:00:00Z',
    } as ReportResponse<ClaimsSummaryRow[]>;
  }

  beforeEach(() => {
    const claims = jasmine.createSpyObj<ClaimsReportService>('ClaimsReportService',
      ['getClaimsPerGroup', 'exportClaimsPerGroupExcel']);
    claims.getClaimsPerGroup.and.returnValue(of(envelope([
      {
        dimensionId: '00000000-0000-0000-0000-000000000001',
        dimensionName: 'Acme Corp',
        holderType: 'GROUP',
        insuranceLine: null,
        currencyCode: 'USD',
        claimCount: 4,
        totalClaimed: '400.00',
        totalApproved: '350.00',
        totalPaid: '300.00',
      },
      {
        dimensionId: '00000000-0000-0000-0000-000000000002',
        dimensionName: 'Jane Doe',
        holderType: 'INDIVIDUAL',
        insuranceLine: null,
        currencyCode: 'USD',
        claimCount: 2,
        totalClaimed: '200.00',
        totalApproved: '180.00',
        totalPaid: '160.00',
      },
    ])));

    const currency = jasmine.createSpyObj<CurrencyService>('CurrencyService', ['listForTenant']);
    currency.listForTenant.and.returnValue(of([]));

    const tenant = jasmine.createSpyObj<TenantService>('TenantService', ['getTenantId']);
    tenant.getTenantId.and.returnValue('11111111-1111-1111-1111-111111111111');

    TestBed.configureTestingModule({
      imports: [GroupClaimsReportComponent],
      providers: [
        provideRouter([]), provideNoopAnimations(),
        { provide: ClaimsReportService, useValue: claims   },
        { provide: CurrencyService,     useValue: currency },
        { provide: TenantService,       useValue: tenant   },
      ],
    });
  });

  it('renders a holder-type chip for every row', () => {
    const fixture = TestBed.createComponent(GroupClaimsReportComponent);
    fixture.detectChanges();

    const chips = fixture.nativeElement.querySelectorAll('.holder-chip') as NodeListOf<HTMLElement>;
    expect(chips.length).toBe(2);
    const labels = Array.from(chips).map(c => c.textContent!.trim());
    expect(labels).toContain('Group');
    expect(labels).toContain('Individual');
  });
});
