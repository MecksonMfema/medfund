import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subject, Subscription } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import {
  ContributionsService,
  Transaction,
  TransactionsPage,
} from '../../../../core/services/contributions.service';
import {
  BillingCatalogueService,
  PaymentMethod,
  TransactionType,
} from '../../../../core/services/billing-catalogue.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { DataTableComponent, TableColumn } from '../../../../shared/components/data-table/data-table.component';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

/**
 * Recorded payments and adjustments. Layout mirrors /tenant/billing/schemes
 * and /tenant/billing/view: page-header banner + <app-data-table> driving
 * server-side pagination + search. Additional filters (currency, type,
 * method, date range) live in a compact filter row above the table.
 *
 * Sub-routes like /transactions/bank set {@code presetTransactionType}
 * via route data — the Type dropdown is hidden and the preset is enforced
 * server-side so the URL identity is preserved.
 */
@Component({
  selector: 'app-transactions-list',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink,
    DataTableComponent, IconComponent, SelectComponent,
  ],
  templateUrl: './transactions-list.component.html',
  styleUrl: './transactions-list.component.scss',
})
export class TransactionsListComponent implements OnInit, OnDestroy {
  // ── Filter catalogue (loaded once on init) ─────────────────────────
  currencies: TenantCurrencyConfig[] = [];
  transactionTypes: TransactionType[] = [];
  paymentMethods: PaymentMethod[] = [];

  // ── Filter state ───────────────────────────────────────────────────
  selectedCurrency = '';
  selectedType = '';
  selectedMethod = '';
  periodStart = '';
  periodEnd = '';
  searchTerm = '';

  /** Preset (route-driven) — locks the Type filter when set so the URL
   *  identity is preserved. Sub-routes like /transactions/bank,
   *  /adjustments, /fc set this via route data instead of query params. */
  presetType = '';
  pageTitle = 'Transactions';
  pageDescription = 'Recorded payments and adjustments. Filter by currency, type, payment method, period, or search transaction / receipt numbers.';

  // ── Server-side pagination state ───────────────────────────────────
  //  Page is 1-indexed in the UI (matches data-table's serverPage input)
  //  and 0-indexed on the API.
  page = 1;
  pageSize = 20;
  totalCount = 0;
  totalPages = 1;

  rows: Transaction[] = [];
  loading = false;
  errorMessage: string | null = null;

  // Debounced free-text search wired to the toolbar input. 350 ms keeps
  // keystrokes from spamming the backend without feeling laggy.
  private readonly search$ = new Subject<string>();
  private searchSub?: Subscription;

  // ── Data-table columns ─────────────────────────────────────────────
  //  Sortable is off across the board because the /transactions endpoint
  //  doesn't accept sortBy/sortDirection yet — we hide the arrows rather
  //  than showing a sort UI that doesn't do anything.
  columns: TableColumn[] = [
    { key: 'transactionDate',   label: 'Date',        sortable: false, type: 'date' },
    { key: 'transactionType',   label: 'Type',        sortable: false, type: 'label' },
    { key: 'paymentMethod',     label: 'Method',      sortable: false, type: 'label' },
    { key: 'amount',            label: 'Amount',      sortable: false, type: 'currency' },
    { key: 'transactionNumber', label: 'Receipt #',   sortable: false },
    { key: 'reference',         label: 'Reference',   sortable: false },
    { key: 'status',            label: 'Status',      sortable: false, type: 'status' },
  ];

  // ── SelectOption factories (memoised via getters is fine — Angular
  //    only re-evaluates when the underlying refs change) ──────────────

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'Any currency' },
      ...this.currencies.map(c => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  get typeOptions(): SelectOption[] {
    return [
      { value: '', label: 'Any type' },
      ...this.transactionTypes.map(t => ({ value: t.code, label: t.label })),
    ];
  }

  get methodOptions(): SelectOption[] {
    return [
      { value: '', label: 'Any method' },
      ...this.paymentMethods.map(m => ({ value: m.code, label: m.label })),
    ];
  }

  constructor(
    private contributions: ContributionsService,
    private catalogue: BillingCatalogueService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private router: Router,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }

    const data = this.route.snapshot.data;
    if (data['presetTransactionType']) {
      this.presetType = data['presetTransactionType'];
      this.selectedType = this.presetType;
    }
    if (data['title']) this.pageTitle = data['title'];
    if (data['description']) this.pageDescription = data['description'];

    this.currencyService.listForTenant(tenantId).subscribe({
      next: (configs) => { this.currencies = configs.filter(c => c.isActive); },
      error: () => {},
    });
    this.catalogue.listTransactionTypes(true).subscribe({
      next: (rows) => { this.transactionTypes = rows; },
      error: () => {},
    });
    this.catalogue.listPaymentMethods(true).subscribe({
      next: (rows) => { this.paymentMethods = rows; },
      error: () => {},
    });

    this.searchSub = this.search$
      .pipe(debounceTime(350), distinctUntilChanged())
      .subscribe((term) => {
        this.searchTerm = term;
        this.page = 1;
        this.fetchPage();
      });

    // Default view — latest transactions for the tenant, no filters.
    // fetchPage() sorts server-side by transactionDate DESC and returns
    // the first {@link pageSize} rows.
    this.fetchPage();
  }

  ngOnDestroy(): void {
    this.searchSub?.unsubscribe();
  }

  fetchPage(): void {
    this.loading = true;
    this.errorMessage = null;
    this.contributions.searchTransactions({
      currency:        this.selectedCurrency || undefined,
      transactionType: this.selectedType     || undefined,
      paymentMethod:   this.selectedMethod   || undefined,
      periodStart:     this.periodStart      || undefined,
      periodEnd:       this.periodEnd        || undefined,
      q:               this.searchTerm       || undefined,
      page:            this.page - 1,
      size:            this.pageSize,
    }).subscribe({
      next: (resp: TransactionsPage) => {
        this.rows = resp.content;
        this.totalCount = resp.total ?? 0;
        this.totalPages = resp.totalPages ?? 1;
        this.page = (resp.page ?? 0) + 1;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load transactions';
        this.loading = false;
      },
    });
  }

  // ── Toolbar handlers ───────────────────────────────────────────────
  /** Toolbar's search input pushes each keystroke through the debounce
   *  pipe so the API is only hit after the operator pauses typing. */
  onSearchInput(value: string): void {
    this.search$.next(value ?? '');
  }

  onPageChange(p: number): void {
    this.page = p;
    this.fetchPage();
  }

  onFilterChange(): void {
    this.page = 1;
    this.fetchPage();
  }

  clearFilters(): void {
    this.selectedCurrency = '';
    this.selectedType = this.presetType;
    this.selectedMethod = '';
    this.periodStart = '';
    this.periodEnd = '';
    this.searchTerm = '';
    this.page = 1;
    this.fetchPage();
  }
}
