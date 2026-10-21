import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  BillingReportParams,
  FinanceService,
  ReportResponse,
  SchemeBillingDetailResponse,
} from '../../../../../core/services/finance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';
import { defaultReportPeriodStart, defaultReportPeriodEnd } from '../shared/report-date-defaults';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { composeWarningsToast, extractErrorMessage } from '../../../../../core/util/http-errors';

/**
 * Drill-through detail for a single scheme's billing — reached by clicking
 * a row on the per-scheme billing report. Renders the scheme identity, the
 * per-currency summary rolled up across the window, and the monthly
 * breakdown (billed vs paid by month). The period defaults to whatever was
 * selected on the parent page (via query params) so navigating back and
 * forth doesn't clobber the window.
 */
@Component({
  selector: 'app-scheme-billing-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, SelectComponent, DataTableComponent],
  templateUrl: './scheme-billing-detail.component.html',
  styleUrl: './billing-report.component.scss',
})
export class SchemeBillingDetailComponent implements OnInit {
  loading = false;

  schemeId = '';
  detail: SchemeBillingDetailResponse | null = null;
  envelope: ReportResponse<SchemeBillingDetailResponse> | null = null;
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
    private route: Router,
    private activated: ActivatedRoute,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.schemeId = this.activated.snapshot.paramMap.get('id') ?? '';
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
    if (!this.schemeId) {
      this.toast.error('No scheme id in the URL');
      return;
    }
    if (!this.periodStart || !this.periodEnd) {
      this.toast.warning('Choose a start and end date.');
      return;
    }
    this.loading = true;
    this.finance.getSchemeBillingDetail(this.schemeId, this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.detail = env.data;
        this.loading = false;
        this.surfaceWarnings(env);
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to load scheme detail'));
        this.detail = null;
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  private surfaceWarnings(env: ReportResponse<SchemeBillingDetailResponse>): void {
    const warnings = env?.warnings ?? [];
    if (warnings.length === 0) return;
    this.toast.warning(composeWarningsToast(warnings, 'Scheme billing detail'), 8000);
  }

  onFilterChange(): void { this.fetch(); }

  back(): void {
    this.route.navigate(['/tenant/finance/reports/schemes'],
      { queryParams: { periodStart: this.periodStart, periodEnd: this.periodEnd } });
  }

  private buildParams(): BillingReportParams {
    return {
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
      reportingCurrency: this.reportingCurrency || undefined,
    };
  }
}

