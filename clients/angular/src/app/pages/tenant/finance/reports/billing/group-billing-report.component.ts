import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  BillingReportParams,
  FinanceService,
  GroupBillingSummaryRow,
  ReportResponse,
} from '../../../../../core/services/finance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';
import { ReportBackButtonComponent } from '../shared/report-back-button.component';
import { defaultReportPeriodStart, defaultReportPeriodEnd } from '../shared/report-date-defaults';

/**
 * Per-holder billing aggregate — one row per (holder, currency). Corporate
 * groups (holderType = GROUP) and individual policyholders (holderType =
 * INDIVIDUAL, member with no group) both appear. Only rows with an
 * invoice_id IS NOT NULL are counted (per Phase 2 plan "committed
 * contributions only").
 */
@Component({
  selector: 'app-group-billing-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, DataTableComponent, ReportBackButtonComponent],
  templateUrl: './group-billing-report.component.html',
  styleUrl: './billing-report.component.scss',
})
export class GroupBillingReportComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  rows: GroupBillingSummaryRow[] = [];
  envelope: ReportResponse<GroupBillingSummaryRow[]> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  periodStart = defaultReportPeriodStart();
  periodEnd   = defaultReportPeriodEnd();
  reportingCurrency = '';

  readonly columns: TableColumn[] = [
    { key: 'groupName',       label: 'Holder',      sortable: false },
    { key: 'holderType',      label: 'Type',        sortable: false, type: 'holderType' },
    { key: 'currencyCode',    label: 'Currency',    sortable: false },
    { key: 'principalCount',  label: 'Principals',  sortable: false },
    { key: 'dependantCount',  label: 'Dependants',  sortable: false },
    { key: 'livesCovered',    label: 'Lives',       sortable: false },
    { key: 'totalBilled',     label: 'Total billed',sortable: false, type: 'currency' },
    { key: 'totalPaid',       label: 'Total paid',  sortable: false, type: 'currency' },
  ];

  constructor(
    private finance: FinanceService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private router: Router,
  ) {}

  onRowClick(row: GroupBillingSummaryRow): void {
    if (!row?.groupId) return;
    // GROUP rows carry a groups.id; INDIVIDUAL rows carry a members.id in
    // the same field. Route to the matching detail surface — one shared
    // component behind two routes serves both.
    const path = row.holderType === 'INDIVIDUAL'
      ? '/tenant/finance/reports/member-billing'
      : '/tenant/finance/reports/group-billing';
    this.router.navigate([path, row.groupId],
      { queryParams: {
          periodStart: this.periodStart,
          periodEnd:   this.periodEnd,
          ...(this.reportingCurrency ? { reportingCurrency: this.reportingCurrency } : {}),
        } });
  }

  ngOnInit(): void {
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
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.finance.getGroupBillingReport(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.rows = env.data ?? [];
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load group billing report';
        this.rows = [];
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.exporting = true;
    this.finance.exportGroupBillingExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `billing-holders-${this.periodStart}-to-${this.periodEnd}.xlsx`);
        this.exporting = false;
      },
      error: () => {
        this.errorMessage = 'Failed to download workbook';
        this.exporting = false;
      },
    });
  }

  onFilterChange(): void { this.fetch(); }

  private buildParams(): BillingReportParams {
    return {
      periodStart: this.periodStart,
      periodEnd:   this.periodEnd,
      reportingCurrency: this.reportingCurrency || undefined,
    };
  }

  get perCurrencyEntries(): { currency: string; totalAmount: string; rowCount: number }[] {
    if (!this.envelope) return [];
    return Object.entries(this.envelope.perCurrency ?? {}).map(([currency, total]) => ({
      currency,
      totalAmount: String(total.totalAmount),
      rowCount: total.rowCount,
    }));
  }
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
