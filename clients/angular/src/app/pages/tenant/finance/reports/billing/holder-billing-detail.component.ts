import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  BillingMonthlyBucket,
  BillingReportParams,
  FinanceService,
  GroupBillingSummaryRow,
  MemberBillingSummaryRow,
  ReportResponse,
} from '../../../../../core/services/finance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';
import { defaultReportPeriodStart, defaultReportPeriodEnd } from '../shared/report-date-defaults';

/** Normalised view of a per-currency summary row so the same template
 *  renders whether the underlying detail is a corporate group or an
 *  individual policyholder. */
interface HolderSummaryRow {
  currencyCode: string;
  principalCount: number;
  dependantCount: number;
  livesCovered: number;
  totalBilled: string;
  totalPaid: string;
}

/**
 * Drill-through detail for a single billing holder — a corporate/employer
 * group (holderType=GROUP) or an individual policyholder
 * (holderType=INDIVIDUAL). Reached by clicking a row on the per-holder
 * billing report. The route's {@code data.holderType} decides which of the
 * two backend endpoints to call
 * ({@code getGroupBillingDetail} vs {@code getMemberBillingDetail}) and
 * how to normalise the response for display. Keeping one component
 * behind two routes means the header, filter strip, per-currency card and
 * monthly breakdown are shared code — the two shapes only diverge in the
 * fetch step.
 */
@Component({
  selector: 'app-holder-billing-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './holder-billing-detail.component.html',
  styleUrl: './billing-report.component.scss',
})
export class HolderBillingDetailComponent implements OnInit {
  loading = false;
  errorMessage: string | null = null;

  holderId = '';
  holderType: 'GROUP' | 'INDIVIDUAL' = 'GROUP';
  holderName = '';
  holderSubtitle = '';
  insuranceLine: string | null = null;
  summary: HolderSummaryRow[] = [];
  monthly: BillingMonthlyBucket[] = [];
  envelope: ReportResponse<unknown> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  periodStart = defaultReportPeriodStart();
  periodEnd   = defaultReportPeriodEnd();
  reportingCurrency = '';

  readonly summaryColumns: TableColumn[] = [
    { key: 'currencyCode',   label: 'Currency',    sortable: false },
    { key: 'principalCount', label: 'Principals',  sortable: false },
    { key: 'dependantCount', label: 'Dependants',  sortable: false },
    { key: 'livesCovered',   label: 'Lives',       sortable: false },
    { key: 'totalBilled',    label: 'Total billed',sortable: false, type: 'currency' },
    { key: 'totalPaid',      label: 'Total paid',  sortable: false, type: 'currency' },
  ];

  readonly monthlyColumns: TableColumn[] = [
    { key: 'periodStart',    label: 'Month',       sortable: false, type: 'date' },
    { key: 'currencyCode',   label: 'Currency',    sortable: false },
    { key: 'principalCount', label: 'Principals',  sortable: false },
    { key: 'dependantCount', label: 'Dependants',  sortable: false },
    { key: 'totalBilled',    label: 'Total billed',sortable: false, type: 'currency' },
    { key: 'totalPaid',      label: 'Total paid',  sortable: false, type: 'currency' },
  ];

  constructor(
    private finance: FinanceService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private router: Router,
    private activated: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.holderId = this.activated.snapshot.paramMap.get('id') ?? '';
    // Route data carries the discriminator; individual routes wire GROUP or
    // INDIVIDUAL explicitly so the URL alone can't ambiguate the fetch.
    const dataType = this.activated.snapshot.data['holderType'];
    if (dataType === 'GROUP' || dataType === 'INDIVIDUAL') this.holderType = dataType;

    const qp = this.activated.snapshot.queryParamMap;
    if (qp.get('periodStart')) this.periodStart = qp.get('periodStart')!;
    if (qp.get('periodEnd'))   this.periodEnd   = qp.get('periodEnd')!;
    if (qp.get('reportingCurrency')) this.reportingCurrency = qp.get('reportingCurrency')!;
    this.loadCurrencies();
    this.fetch();
  }

  private loadCurrencies(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.currencyService.listForTenant(tenantId).subscribe({
      next: cs => {
        this.currencies = cs.filter(c => c.isActive);
        const def = this.currencies.find(c => c.isDefault);
        if (def && !this.reportingCurrency) this.reportingCurrency = def.currencyCode;
      },
      error: () => { /* non-fatal */ },
    });
  }

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'Tenant default' },
      ...this.currencies.map(c => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  fetch(): void {
    if (!this.holderId) {
      this.errorMessage = 'No holder id in the URL';
      return;
    }
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    if (this.holderType === 'GROUP') {
      this.finance.getGroupBillingDetail(this.holderId, this.buildParams()).subscribe({
        next: env => {
          this.envelope = env;
          this.holderName    = env.data?.groupName || '';
          this.holderSubtitle = 'Corporate / employer group';
          this.insuranceLine = null;
          this.summary = (env.data?.perCurrencySummary || []).map(r => this.fromGroupRow(r));
          this.monthly = env.data?.monthlyBreakdown || [];
          this.loading = false;
        },
        error: err => this.onError(err),
      });
    } else {
      this.finance.getMemberBillingDetail(this.holderId, this.buildParams()).subscribe({
        next: env => {
          this.envelope = env;
          const d = env.data;
          this.holderName    = d?.memberName || '';
          this.holderSubtitle = d?.memberNumber
            ? `Individual policyholder · ${d.memberNumber}`
            : 'Individual policyholder';
          this.insuranceLine = d?.insuranceLine || null;
          this.summary = (d?.summary || []).map(r => this.fromMemberRow(r));
          this.monthly = d?.monthly || [];
          this.loading = false;
        },
        error: err => this.onError(err),
      });
    }
  }

  private onError(err: any): void {
    this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load holder detail';
    this.summary = [];
    this.monthly = [];
    this.envelope = null;
    this.loading = false;
  }

  onFilterChange(): void { this.fetch(); }

  back(): void {
    this.router.navigate(['/tenant/finance/reports/group-billing'],
      { queryParams: { periodStart: this.periodStart, periodEnd: this.periodEnd } });
  }

  private buildParams(): BillingReportParams {
    return {
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
      reportingCurrency: this.reportingCurrency || undefined,
    };
  }

  private fromGroupRow(r: GroupBillingSummaryRow): HolderSummaryRow {
    return {
      currencyCode: r.currencyCode,
      principalCount: r.principalCount,
      dependantCount: r.dependantCount,
      livesCovered: r.livesCovered,
      totalBilled: r.totalBilled,
      totalPaid: r.totalPaid,
    };
  }

  // Members: no lives-covered concept per row — a single member is 1 life.
  // Principal count = the contribution count (dependant vs principal split
  // is not modelled on the per-member DTO); dependantCount left at 0.
  private fromMemberRow(r: MemberBillingSummaryRow): HolderSummaryRow {
    return {
      currencyCode: r.currencyCode,
      principalCount: r.contributionCount,
      dependantCount: 0,
      livesCovered: 1,
      totalBilled: r.totalBilled,
      totalPaid: r.totalPaid,
    };
  }
}

