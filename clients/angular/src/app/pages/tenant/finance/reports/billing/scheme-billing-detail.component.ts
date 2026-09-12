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
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';

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
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, DataTableComponent],
  templateUrl: './scheme-billing-detail.component.html',
  styleUrl: './billing-report.component.scss',
})
export class SchemeBillingDetailComponent implements OnInit {
  loading = false;
  errorMessage: string | null = null;

  schemeId = '';
  detail: SchemeBillingDetailResponse | null = null;
  envelope: ReportResponse<SchemeBillingDetailResponse> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  periodStart = firstOfPriorMonth();
  periodEnd   = lastOfPriorMonth();
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
      this.errorMessage = 'No scheme id in the URL';
      return;
    }
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.finance.getSchemeBillingDetail(this.schemeId, this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.detail = env.data;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load scheme detail';
        this.detail = null;
        this.envelope = null;
        this.loading = false;
      },
    });
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

function firstOfPriorMonth(): string {
  const now = new Date();
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1));
  return d.toISOString().slice(0, 10);
}
function lastOfPriorMonth(): string {
  const now = new Date();
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 0));
  return d.toISOString().slice(0, 10);
}
