import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  FinanceService,
  ProviderBalance,
} from '../../../../core/services/finance.service';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';
import { CurrencyTotal, aggregateByCurrency } from '../../../../shared/utils/currency-totals';

@Component({
  selector: 'app-creditors-list',
  standalone: true,
  imports: [CommonModule, FormsModule, SelectComponent, SkeletonComponent, CurrencyFormatPipe],
  templateUrl: './creditors-list.component.html',
  styleUrl: './creditors-list.component.scss',
})
export class CreditorsListComponent implements OnInit {
  rows: ProviderBalance[] = [];
  filtered: ProviderBalance[] = [];
  loading = false;
  errorMessage: string | null = null;

  currencyFilter = '';
  searchTerm = '';
  sortBy: 'outstanding' | 'paid' | 'claimed' = 'outstanding';

  constructor(private finance: FinanceService, private router: Router) {}

  readonly sortByOptions: SelectOption[] = [
    { value: 'outstanding', label: 'Outstanding' },
    { value: 'paid', label: 'Paid' },
    { value: 'claimed', label: 'Claimed' },
  ];

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'All' },
      ...this.uniqueCurrencies().map(c => ({ value: c, label: c })),
    ];
  }

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    this.finance.listProviderBalances().subscribe({
      next: (rows) => { this.rows = rows; this.applyFilter(); this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load provider balances';
        this.loading = false;
      },
    });
  }

  open(balance: ProviderBalance): void {
    this.router.navigate(['/tenant/finance/creditors/provider', balance.providerId]);
  }

  onFilterChange(): void { this.applyFilter(); }

  uniqueCurrencies(): string[] {
    return Array.from(new Set(this.rows.map(r => r.currencyCode))).sort();
  }

  /**
   * Per-currency totals for the currently filtered rows. Critical Rule 1
   * forbids summing across currencies, so the page renders one chip per
   * currency rather than collapsing to a single number.
   */
  totalsByCurrency(): CurrencyTotal[] {
    return aggregateByCurrency(this.filtered, 'outstandingBalance', 'currencyCode');
  }

  private applyFilter(): void {
    let rows = [...this.rows];
    if (this.currencyFilter) {
      rows = rows.filter(r => r.currencyCode === this.currencyFilter);
    }
    const q = this.searchTerm.trim().toLowerCase();
    if (q) {
      rows = rows.filter(r => r.providerId?.toLowerCase().includes(q));
    }
    rows.sort((a, b) => {
      const key = this.sortBy === 'paid' ? 'totalPaid' : this.sortBy === 'claimed' ? 'totalClaimed' : 'outstandingBalance';
      return Number(b[key as keyof ProviderBalance]) - Number(a[key as keyof ProviderBalance]);
    });
    this.filtered = rows;
  }
}
