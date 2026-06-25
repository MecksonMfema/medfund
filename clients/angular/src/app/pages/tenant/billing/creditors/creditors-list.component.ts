import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { BalanceService, CreditorRow, PageResponse } from '../../../../core/services/balance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-creditors-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, SkeletonComponent, CurrencyFormatPipe, HumanizePipe],
  templateUrl: './creditors-list.component.html',
  styleUrl: './creditors-list.component.scss',
})
export class CreditorsListComponent implements OnInit {
  currencies: TenantCurrencyConfig[] = [];
  selectedCurrency: string = '';
  search = '';
  page = 0;
  size = 20;
  loading = false;
  errorMessage: string | null = null;

  rows: CreditorRow[] = [];
  totalRows = 0;
  totalPages = 1;

  private searchInput$ = new Subject<string>();

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({
      value: c.currencyCode,
      label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
    }));
  }

  constructor(
    private balanceService: BalanceService,
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

    this.searchInput$.pipe(debounceTime(400), distinctUntilChanged())
      .subscribe(() => { this.page = 0; this.refresh(); });
  }

  onSearchChange(value: string): void {
    this.search = value;
    this.searchInput$.next(value);
  }

  onCurrencyChange(): void {
    this.page = 0;
    this.refresh();
  }

  refresh(): void {
    if (!this.selectedCurrency) return;
    this.loading = true;
    this.errorMessage = null;
    this.balanceService.listCreditors(this.selectedCurrency, this.search, this.page, this.size).subscribe({
      next: (resp: PageResponse<CreditorRow>) => {
        this.rows = resp.content;
        this.totalRows = resp.total;
        this.totalPages = resp.totalPages;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load creditors';
        this.loading = false;
      },
    });
  }

  prevPage(): void { if (this.page > 0) { this.page--; this.refresh(); } }
  nextPage(): void { if (this.page < this.totalPages - 1) { this.page++; this.refresh(); } }
}
