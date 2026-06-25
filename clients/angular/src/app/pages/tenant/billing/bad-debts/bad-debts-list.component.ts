import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { BadDebtRow, BalanceService, PageResponse } from '../../../../core/services/balance.service';
import { BillingCatalogueService, DunningConfig } from '../../../../core/services/billing-catalogue.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-bad-debts-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './bad-debts-list.component.html',
  styleUrl: './bad-debts-list.component.scss',
})
export class BadDebtsListComponent implements OnInit {
  currencies: TenantCurrencyConfig[] = [];
  selectedCurrency = '';
  search = '';
  page = 0;
  size = 20;
  minAgeDays: number | null = null;
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  rows: BadDebtRow[] = [];
  totalRows = 0;
  totalPages = 1;

  dunning: DunningConfig | null = null;

  flagInputContributionId = '';
  flagInputReason = '';
  flagPendingRow: BadDebtRow | null = null;

  private searchInput$ = new Subject<string>();

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({
      value: c.currencyCode,
      label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
    }));
  }

  constructor(
    private balanceService: BalanceService,
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
        this.currencies = configs.filter(c => c.isActive && c.isBillingCurrency);
        const def = this.currencies.find(c => c.isDefault);
        this.selectedCurrency = (def ?? this.currencies[0])?.currencyCode ?? '';
        if (this.selectedCurrency) this.refresh();
      },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Failed to load currencies'; },
    });

    this.catalogue.getDunning().subscribe({
      next: (d) => { this.dunning = d; this.minAgeDays = d.suspensionDays; },
      error: () => { /* keep default minAgeDays unset; backend supplies fallback */ },
    });

    this.searchInput$.pipe(debounceTime(400), distinctUntilChanged())
      .subscribe(() => { this.page = 0; this.refresh(); });
  }

  onSearchChange(v: string) { this.search = v; this.searchInput$.next(v); }
  onCurrencyChange() { this.page = 0; this.refresh(); }
  onMinAgeChange()   { this.page = 0; this.refresh(); }

  refresh(): void {
    if (!this.selectedCurrency) return;
    this.loading = true;
    this.errorMessage = null;
    this.balanceService.listAged(
      this.selectedCurrency,
      this.minAgeDays ?? undefined,
      this.search || undefined,
      this.page, this.size,
    ).subscribe({
      next: (resp: PageResponse<BadDebtRow>) => {
        this.rows = resp.content;
        this.totalRows = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load bad debts';
        this.loading = false;
      },
    });
  }

  prevPage() { if (this.page > 0) { this.page--; this.refresh(); } }
  nextPage() { if (this.page < this.totalPages - 1) { this.page++; this.refresh(); } }

  startFlag(row: BadDebtRow): void {
    this.flagPendingRow = row;
    this.flagInputContributionId = '';
    this.flagInputReason = '';
  }

  cancelFlag(): void {
    this.flagPendingRow = null;
  }

  confirmFlag(): void {
    if (!this.flagInputContributionId.trim()) {
      this.errorMessage = 'Provide the contribution id to flag';
      return;
    }
    this.balanceService.flagBadDebt({
      contributionId: this.flagInputContributionId.trim(),
      reason: this.flagInputReason.trim() || undefined,
    }).subscribe({
      next: () => {
        this.successMessage = 'Bad debt flagged';
        setTimeout(() => (this.successMessage = null), 3000);
        this.flagPendingRow = null;
        this.refresh();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Flag failed';
      },
    });
  }
}
