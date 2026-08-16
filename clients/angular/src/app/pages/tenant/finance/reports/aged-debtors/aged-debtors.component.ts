import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, Subscription, debounceTime, distinctUntilChanged } from 'rxjs';
import { DataTableComponent, TableColumn } from '../../../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { BadDebtRow, BalanceService, PageResponse } from '../../../../../core/services/balance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { ToastService } from '../../../../../shared/components/toast/toast.service';

/**
 * Aged-debtors report page (Phase 8, D8-2 + D8-8) — a catalogue-registered
 * report surface replacing the old `debtors-report` ComingSoon stub. Reuses
 * the Phase 1 `/billing/balances/aged-balances` backend: GRACE / SUSPENDED /
 * WRITE_OFF aging grid + XLSX export. Route key is AGED_BALANCES so the
 * tenant toggle gates this page's API calls.
 */
@Component({
  selector: 'app-aged-debtors',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent, IconComponent, SelectComponent],
  templateUrl: './aged-debtors.component.html',
  styleUrl: './aged-debtors.component.scss',
})
export class AgedDebtorsComponent implements OnInit, OnDestroy {
  rows: BadDebtRow[] = [];

  page = 1;
  pageSize = 20;
  totalCount = 0;
  totalPages = 1;
  loading = false;
  exporting = false;
  searchTerm = '';
  minAgeDays: number | null = null;
  minAgeDaysInput = '';
  errorMessage: string | null = null;

  currencies: TenantCurrencyConfig[] = [];
  selectedCurrency = '';

  columns: TableColumn[] = [
    { key: 'subjectType',   label: 'Type',        type: 'status' },
    { key: 'subjectName',   label: 'Name' },
    { key: 'subjectCode',   label: 'Code' },
    { key: 'subjectEmail',  label: 'Email' },
    { key: 'agingStatus',   label: 'Aging',       type: 'status' },
    { key: 'balance',       label: 'Outstanding', type: 'currency', class: 'right' },
    { key: 'lastPaymentAt', label: 'Last payment', type: 'date' },
    { key: 'daysSinceLastActivity', label: 'Days since', class: 'right' },
  ];

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({
      value: c.currencyCode,
      label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
    }));
  }

  private searchInput$ = new Subject<string>();
  private subs: Subscription[] = [];

  constructor(
    private balanceService: BalanceService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }

    this.currencyService.listForTenant(tenantId).subscribe({
      next: (configs) => {
        this.currencies = configs.filter(c => c.isActive && c.isBillingCurrency);
        const def = this.currencies.find(c => c.isDefault);
        this.selectedCurrency = (def ?? this.currencies[0])?.currencyCode ?? '';
        if (this.selectedCurrency) this.fetchPage();
      },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Failed to load currencies'; },
    });

    this.subs.push(
      this.searchInput$.pipe(debounceTime(400), distinctUntilChanged()).subscribe(() => {
        this.page = 1;
        this.fetchPage();
      }),
    );
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  fetchPage(): void {
    if (!this.selectedCurrency) return;
    this.loading = true;
    this.errorMessage = null;
    this.balanceService.listAged(
      this.selectedCurrency,
      this.minAgeDays ?? undefined,
      this.searchTerm || undefined,
      this.page - 1,
      this.pageSize,
    ).subscribe({
      next: (resp: PageResponse<BadDebtRow>) => {
        this.rows       = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading    = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load aged balances';
        this.rows       = [];
        this.totalCount = 0;
        this.totalPages = 1;
        this.loading    = false;
      },
    });
  }

  onCurrencyChange(): void {
    this.page = 1;
    this.fetchPage();
  }

  onMinAgeChange(): void {
    this.minAgeDays = this.minAgeDaysInput.trim() ? Number(this.minAgeDaysInput) : null;
    this.page = 1;
    this.fetchPage();
  }

  onSearch(term: string): void {
    this.searchTerm = term;
    this.searchInput$.next(term);
  }

  onPageChange(page: number): void {
    this.page = page;
    this.fetchPage();
  }

  exportExcel(): void {
    if (!this.selectedCurrency || this.exporting) return;
    this.exporting = true;
    this.balanceService.exportAgedExcel(
      this.selectedCurrency,
      this.minAgeDays ?? undefined,
      this.searchTerm || undefined,
    ).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `aged-balances-${this.selectedCurrency}-${new Date().toISOString().slice(0, 10)}.xlsx`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        this.exporting = false;
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || 'Failed to export aged balances');
        this.exporting = false;
      },
    });
  }
}
