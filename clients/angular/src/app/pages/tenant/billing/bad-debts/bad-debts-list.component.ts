import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, Subscription, debounceTime, distinctUntilChanged } from 'rxjs';
import { DataTableComponent, TableColumn } from '../../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { BalanceService, CreditorRow, PageResponse } from '../../../../core/services/balance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

type SubjectType = 'MEMBER' | 'GROUP';

/**
 * Tab keying — one tab per subjectType a tenant actually bills. For
 * {@code BOTH}-model tenants the strip renders Individuals + Groups; for
 * single-model tenants the strip is hidden and the subjectType filter is
 * pinned to whichever half applies. Kept structurally identical to the
 * creditors page so the two views feel like halves of one system.
 */
interface SubjectTab {
  value: SubjectType | null;   // null = show both halves (BOTH-model default)
  label: string;
}

@Component({
  selector: 'app-bad-debts-list',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent, IconComponent, SelectComponent],
  templateUrl: './bad-debts-list.component.html',
  styleUrl: './bad-debts-list.component.scss',
})
export class BadDebtsListComponent implements OnInit, OnDestroy {
  rows: CreditorRow[] = [];

  // Server-side pagination + search. Page is 1-indexed in the UI (matches
  // the data-table's serverPage input) and 0-indexed in the API layer.
  page = 1;
  pageSize = 20;
  totalCount = 0;
  totalPages = 1;
  loading = false;
  exporting = false;
  searchTerm = '';
  errorMessage: string | null = null;

  currencies: TenantCurrencyConfig[] = [];
  selectedCurrency = '';

  tabs: SubjectTab[] = [];
  activeTab: SubjectType | null = null;
  showTabs = false;

  columns: TableColumn[] = [
    { key: 'subjectType',  label: 'Type',        type: 'status' },
    { key: 'subjectName',  label: 'Name' },
    { key: 'subjectCode',  label: 'Code' },
    { key: 'subjectEmail', label: 'Email' },
    { key: 'balance',      label: 'Outstanding', type: 'currency', class: 'right' },
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
    this.subs.push(
      this.tenantService.tenant$.subscribe(t => this.rebuildTabs(t?.membershipModel ?? 'BOTH')),
    );

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

  private rebuildTabs(model: 'INDIVIDUAL_ONLY' | 'GROUP_ONLY' | 'BOTH'): void {
    switch (model) {
      case 'INDIVIDUAL_ONLY':
        this.tabs = [];
        this.showTabs = false;
        this.activeTab = 'MEMBER';
        break;
      case 'GROUP_ONLY':
        this.tabs = [];
        this.showTabs = false;
        this.activeTab = 'GROUP';
        break;
      case 'BOTH':
      default:
        this.tabs = [
          { value: null,     label: 'All' },
          { value: 'MEMBER', label: 'Individuals' },
          { value: 'GROUP',  label: 'Groups' },
        ];
        this.showTabs = true;
        if (this.activeTab === undefined) this.activeTab = null;
        break;
    }
    if (this.selectedCurrency) this.fetchPage();
  }

  selectTab(value: SubjectType | null): void {
    if (this.activeTab === value) return;
    this.activeTab = value;
    this.page = 1;
    this.fetchPage();
  }

  fetchPage(): void {
    if (!this.selectedCurrency) return;
    this.loading = true;
    this.errorMessage = null;
    this.balanceService.listBadDebts(
      this.selectedCurrency,
      this.activeTab ?? undefined,
      this.searchTerm || undefined,
      this.page - 1,
      this.pageSize,
    ).subscribe({
      next: (resp: PageResponse<CreditorRow>) => {
        this.rows       = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading    = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load bad debts';
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

  /**
   * Download the current filter (currency + tab + search) as .xlsx.
   * Backend applies the same params as {@link fetchPage} so the sheet
   * mirrors exactly what the operator sees on screen.
   */
  exportExcel(): void {
    if (!this.selectedCurrency || this.exporting) return;
    this.exporting = true;
    this.balanceService.exportBadDebtsExcel(
      this.selectedCurrency,
      this.activeTab ?? undefined,
      this.searchTerm || undefined,
    ).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        const tabSlug = this.activeTab ? '-' + this.activeTab.toLowerCase() : '';
        a.download = `bad-debts-${this.selectedCurrency}${tabSlug}-${new Date().toISOString().slice(0, 10)}.xlsx`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        this.exporting = false;
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || 'Failed to export bad debts');
        this.exporting = false;
      },
    });
  }
}
