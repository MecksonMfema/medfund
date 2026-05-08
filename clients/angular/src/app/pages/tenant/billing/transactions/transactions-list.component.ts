import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject } from 'rxjs';
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
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-transactions-list',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink,
    IconComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe,
  ],
  templateUrl: './transactions-list.component.html',
  styleUrl: './transactions-list.component.scss',
})
export class TransactionsListComponent implements OnInit {
  // Filter state
  currencies: TenantCurrencyConfig[] = [];
  transactionTypes: TransactionType[] = [];
  paymentMethods: PaymentMethod[] = [];

  selectedCurrency = '';
  selectedType = '';
  selectedMethod = '';
  periodStart = '';
  periodEnd = '';
  search = '';

  // Pagination
  page = 0;
  size = 20;
  rows: Transaction[] = [];
  totalRows = 0;
  totalPages = 1;

  loading = false;
  errorMessage: string | null = null;

  private searchInput$ = new Subject<string>();

  constructor(
    private contributions: ContributionsService,
    private catalogue: BillingCatalogueService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    this.currencyService.listForTenant(tenantId).subscribe({
      next: (configs) => {
        this.currencies = configs.filter(c => c.isActive);
      },
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

    this.searchInput$.pipe(debounceTime(400), distinctUntilChanged())
      .subscribe(() => { this.page = 0; this.refresh(); });

    this.refresh();
  }

  onSearchChange(value: string): void {
    this.search = value;
    this.searchInput$.next(value);
  }

  onFilterChange(): void {
    this.page = 0;
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    this.errorMessage = null;
    this.contributions.searchTransactions({
      currency: this.selectedCurrency || undefined,
      transactionType: this.selectedType || undefined,
      paymentMethod: this.selectedMethod || undefined,
      periodStart: this.periodStart || undefined,
      periodEnd: this.periodEnd || undefined,
      q: this.search || undefined,
      page: this.page,
      size: this.size,
    }).subscribe({
      next: (resp: TransactionsPage) => {
        this.rows = resp.content;
        this.totalRows = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load transactions';
        this.loading = false;
      },
    });
  }

  clearFilters(): void {
    this.selectedCurrency = '';
    this.selectedType = '';
    this.selectedMethod = '';
    this.periodStart = '';
    this.periodEnd = '';
    this.search = '';
    this.page = 0;
    this.refresh();
  }

  prevPage(): void { if (this.page > 0) { this.page--; this.refresh(); } }
  nextPage(): void { if (this.page < this.totalPages - 1) { this.page++; this.refresh(); } }
}
