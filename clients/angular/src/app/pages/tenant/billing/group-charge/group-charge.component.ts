import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { BalanceService, GroupBalance } from '../../../../core/services/balance.service';
import { ContributionsService, GroupOption } from '../../../../core/services/contributions.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { CurrencyFormatPipe } from '../../../../shared/pipes/currency-format.pipe';

@Component({
  selector: 'app-group-charge',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, CurrencyFormatPipe],
  templateUrl: './group-charge.component.html',
  styleUrl: './group-charge.component.scss',
})
export class GroupChargeComponent implements OnInit {
  currencies: TenantCurrencyConfig[] = [];
  selectedCurrency = '';

  // Autocomplete state
  groupQuery = '';
  groupOptions: GroupOption[] = [];
  selectedGroup: GroupOption | null = null;
  searching = false;
  showSuggestions = false;
  highlightIndex = -1;

  balance: GroupBalance | null = null;
  loading = false;
  errorMessage: string | null = null;

  private query$ = new Subject<string>();

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({
      value: c.currencyCode,
      label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
    }));
  }

  constructor(
    private balanceService: BalanceService,
    private contributionsService: ContributionsService,
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
      },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Failed to load currencies'; },
    });

    this.query$.pipe(
      debounceTime(250),
      distinctUntilChanged(),
      switchMap(q => {
        this.searching = true;
        return this.contributionsService.searchGroups(q);
      }),
    ).subscribe({
      next: (rows) => {
        this.groupOptions = rows;
        this.searching = false;
        this.highlightIndex = -1;
      },
      error: (err) => {
        this.searching = false;
        this.errorMessage = err?.error?.detail || 'Group lookup failed';
      },
    });
  }

  onQueryChange(value: string): void {
    this.groupQuery = value;
    this.showSuggestions = true;
    // Edits after a pick clear the selection so the lookup button stays honest.
    if (this.selectedGroup && value !== this.groupLabel(this.selectedGroup)) {
      this.selectedGroup = null;
      this.balance = null;
    }
    this.query$.next(value);
  }

  onFocus(): void {
    this.showSuggestions = true;
    if (this.groupOptions.length === 0) this.query$.next(this.groupQuery);
  }

  /**
   * Hide on blur, but defer so a click on a suggestion is processed first.
   * Without the timeout, mousedown→blur fires before mouseup→click and the
   * dropdown disappears before the click reaches the option row.
   */
  onBlur(): void {
    setTimeout(() => (this.showSuggestions = false), 150);
  }

  pick(opt: GroupOption): void {
    this.selectedGroup = opt;
    this.groupQuery = this.groupLabel(opt);
    this.showSuggestions = false;
    this.balance = null;
  }

  groupLabel(opt: GroupOption): string {
    return opt.registrationNumber ? `${opt.name} · ${opt.registrationNumber}` : opt.name;
  }

  onKeydown(event: KeyboardEvent): void {
    if (!this.showSuggestions || this.groupOptions.length === 0) return;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.highlightIndex = (this.highlightIndex + 1) % this.groupOptions.length;
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.highlightIndex =
        this.highlightIndex <= 0 ? this.groupOptions.length - 1 : this.highlightIndex - 1;
    } else if (event.key === 'Enter' && this.highlightIndex >= 0) {
      event.preventDefault();
      this.pick(this.groupOptions[this.highlightIndex]);
    } else if (event.key === 'Escape') {
      this.showSuggestions = false;
    }
  }

  lookup(): void {
    if (!this.selectedGroup || !this.selectedCurrency) {
      this.errorMessage = 'Pick a group from the suggestions and choose a currency';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.balance = null;
    this.balanceService.getGroupBalance(this.selectedGroup.id, this.selectedCurrency).subscribe({
      next: (b) => { this.balance = b; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load group balance';
        this.loading = false;
      },
    });
  }

  daysSince(iso?: string): number | null {
    if (!iso) return null;
    return Math.floor((Date.now() - new Date(iso).getTime()) / 86400000);
  }
}
