import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subject, Subscription, debounceTime, distinctUntilChanged } from 'rxjs';
import {
  CreditorPageParams,
  CreditorRow,
  CreditorSubjectType,
  FinancePageResponse,
  FinanceService,
  ReportResponse,
} from '../../../../core/services/finance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { DataTableComponent, TableAction, TableColumn } from '../../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

type SubjectTabValue = CreditorSubjectType | 'BOTH';

interface SubjectTab {
  value: SubjectTabValue;
  label: string;
}

/**
 * Unified creditors listing — providers and members side by side. Every
 * row carries the same four money columns
 * ({@code totalClaimed / totalApproved / totalPaid / outstandingBalance});
 * {@code subjectType} disambiguates the row and drives the detail navigation.
 */
@Component({
  selector: 'app-creditors-list',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent, IconComponent, SelectComponent],
  templateUrl: './creditors-list.component.html',
  styleUrl: './creditors-list.component.scss',
})
export class CreditorsListComponent implements OnInit, OnDestroy {
  rows: CreditorRow[] = [];
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  // Subject-type tab strip. BOTH is the natural landing view; the two
  // typed tabs let an operator focus one half without changing the
  // outer surface.
  readonly tabs: SubjectTab[] = [
    { value: 'BOTH',     label: 'All' },
    { value: 'PROVIDER', label: 'Providers' },
    { value: 'MEMBER',   label: 'Members' },
  ];
  activeTab: SubjectTabValue = 'BOTH';

  // Currency filter — optional; empty string means "all currencies".
  currencies: TenantCurrencyConfig[] = [];
  selectedCurrency = '';

  // Server-side pagination / sort / search.
  page = 1;
  pageSize = 50;
  totalCount = 0;
  totalPages = 1;
  sortKey = 'outstandingBalance';
  sortDirection: 'asc' | 'desc' = 'desc';
  searchTerm = '';

  readonly columns: TableColumn[] = [
    { key: 'subjectType',        label: 'Type',        sortable: false, type: 'status' },
    { key: 'subjectCode',        label: 'Code' },
    { key: 'subjectName',        label: 'Name',        sortable: true },
    { key: 'currencyCode',       label: 'Currency',    sortable: true },
    { key: 'totalClaimed',       label: 'Claimed',     sortable: true, type: 'currency' },
    { key: 'totalApproved',      label: 'Approved',    sortable: true, type: 'currency' },
    { key: 'totalPaid',          label: 'Paid',        sortable: true, type: 'currency' },
    { key: 'outstandingBalance', label: 'Outstanding', sortable: true, type: 'currency' },
    { key: 'lastActivityAt',     label: 'Last activity', sortable: true, type: 'date' },
  ];

  readonly actions: TableAction[] = [
    {
      label: 'View',
      icon: 'eye',
      color: 'default',
      handler: (row: CreditorRow) => this.openDetail(row),
    },
  ];

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'All currencies' },
      ...this.currencies.map(c => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  private searchInput$ = new Subject<string>();
  private subs: Subscription[] = [];

  constructor(
    private finance: FinanceService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private toast: ToastService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (tenantId) {
      this.currencyService.listForTenant(tenantId).subscribe({
        next: (configs) => {
          this.currencies = configs.filter(c => c.isActive && c.isBillingCurrency);
        },
        error: () => { this.currencies = []; },
      });
    }

    this.subs.push(
      this.searchInput$.pipe(debounceTime(400), distinctUntilChanged()).subscribe(() => {
        this.page = 1;
        this.fetchPage();
      }),
    );

    this.fetchPage();
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  selectTab(value: SubjectTabValue): void {
    if (this.activeTab === value) return;
    this.activeTab = value;
    this.page = 1;
    this.fetchPage();
  }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    const opts: CreditorPageParams = {
      subjectType: this.activeTab,
      currencyCode: this.selectedCurrency || undefined,
      q: this.searchTerm || undefined,
      sortKey: this.sortKey,
      sortDirection: this.sortDirection,
      page: this.page - 1,
      size: this.pageSize,
    };
    this.finance.listCreditorsPaged(opts).subscribe({
      next: (envelope: ReportResponse<FinancePageResponse<CreditorRow>>) => {
        const page = envelope.data;
        this.rows       = page.content;
        this.totalCount = page.total;
        this.totalPages = page.totalPages;
        this.loading    = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load creditors';
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

  onSearch(term: string): void {
    this.searchTerm = term;
    this.searchInput$.next(term);
  }

  onPageChange(page: number): void {
    this.page = page;
    this.fetchPage();
  }

  onSortChange(evt: { key: string; direction: 'asc' | 'desc' }): void {
    this.sortKey = evt.key;
    this.sortDirection = evt.direction;
    this.page = 1;
    this.fetchPage();
  }

  private openDetail(row: CreditorRow): void {
    const segment = row.subjectType === 'MEMBER' ? 'member' : 'provider';
    this.router.navigate(['/tenant/finance/creditors', segment, row.subjectId]);
  }

  /**
   * Download the current filter (subject-type + currency + search) as .xlsx.
   * Backend uses the same params as {@link fetchPage} so the sheet mirrors
   * what the operator sees on screen.
   */
  exportExcel(): void {
    if (this.exporting) return;
    this.exporting = true;
    this.finance.exportCreditorsExcel({
      subjectType: this.activeTab,
      currencyCode: this.selectedCurrency || undefined,
      q: this.searchTerm || undefined,
    }).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        const currencySlug = this.selectedCurrency || 'ALL';
        a.download = `creditors-${this.activeTab}-${currencySlug}-${new Date().toISOString().slice(0, 10)}.xlsx`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        this.exporting = false;
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || 'Failed to export creditors');
        this.exporting = false;
      },
    });
  }
}
