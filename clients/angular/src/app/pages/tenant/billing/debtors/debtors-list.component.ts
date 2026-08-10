import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, Subscription, debounceTime, distinctUntilChanged } from 'rxjs';
import { DataTableComponent, TableColumn } from '../../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { BalanceService, DebtorRow, PageResponse } from '../../../../core/services/balance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

type SubjectType = 'MEMBER' | 'GROUP';

/**
 * Tab keying — one tab per subjectType a tenant actually bills. For
 * {@code BOTH}-model tenants the strip renders Individuals + Groups; for
 * single-model tenants the strip is hidden and the subjectType filter is
 * pinned to whichever half applies.
 */
interface SubjectTab {
  value: SubjectType | null;   // null = show both halves (BOTH-model default)
  label: string;
}

@Component({
  selector: 'app-debtors-list',
  standalone: true,
  imports: [CommonModule, FormsModule, DataTableComponent, IconComponent, SelectComponent],
  templateUrl: './debtors-list.component.html',
  styleUrl: './debtors-list.component.scss',
})
export class DebtorsListComponent implements OnInit, OnDestroy {
  rows: DebtorRow[] = [];

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

  // Currency filter — kept as a select rather than tabs because the
  // subject-type dimension already claims the tab strip.
  currencies: TenantCurrencyConfig[] = [];
  selectedCurrency = '';

  // Subject-type tabs. Only rendered when the tenant runs BOTH billing
  // models — a single-model tenant has nothing to disambiguate. See
  // rebuildTabs() for the derivation.
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
    // Tabs are derived from tenant.membershipModel — subscribe so a
    // tenant switch flips the strip without a reload.
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

    // Debounced search — server-side query fires 400ms after the last
    // keystroke to avoid a request per character.
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
        this.activeTab = 'MEMBER';   // pin server-side filter to individuals only
        break;
      case 'GROUP_ONLY':
        this.tabs = [];
        this.showTabs = false;
        this.activeTab = 'GROUP';    // pin to groups only
        break;
      case 'BOTH':
      default:
        this.tabs = [
          { value: null,     label: 'All' },
          { value: 'MEMBER', label: 'Individuals' },
          { value: 'GROUP',  label: 'Groups' },
        ];
        this.showTabs = true;
        // Default to 'All' on the very first render so the operator
        // sees the full receivables list before drilling in with a
        // tab click. Preserve the current selection on subsequent
        // rebuilds (tenant-model change while page is open).
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
    this.balanceService.listDebtors(
      this.selectedCurrency,
      this.activeTab ?? undefined,
      this.searchTerm || undefined,
      this.page - 1,
      this.pageSize,
    ).subscribe({
      next: (resp: PageResponse<DebtorRow>) => {
        this.rows       = resp.content;
        this.totalCount = resp.total;
        this.totalPages = resp.totalPages;
        this.loading    = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load debtors';
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
   * Backend uses the same params as {@link fetchPage} so the sheet
   * mirrors exactly what the operator sees on screen.
   */
  exportExcel(): void {
    if (!this.selectedCurrency || this.exporting) return;
    this.exporting = true;
    this.balanceService.exportDebtorsExcel(
      this.selectedCurrency,
      this.activeTab ?? undefined,
      this.searchTerm || undefined,
    ).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        const tabSlug = this.activeTab ? '-' + this.activeTab.toLowerCase() : '';
        a.download = `debtors-${this.selectedCurrency}${tabSlug}-${new Date().toISOString().slice(0, 10)}.xlsx`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        this.exporting = false;
      },
      error: (err) => {
        this.toast.error(err?.error?.detail || 'Failed to export debtors');
        this.exporting = false;
      },
    });
  }
}
