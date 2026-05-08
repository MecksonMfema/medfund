import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import {
  CurrencyService,
  Currency,
  ExchangeRate,
  TenantCurrencyConfig,
} from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';

interface CurrencyRow extends TenantCurrencyConfig {
  name?: string;
  symbol?: string;
}

/**
 * Tenant currency management — embedded as a tab inside the IT-admin Settings
 * page. Three sections rendered inline so the admin doesn't navigate away:
 * <ul>
 *   <li>Configured currencies — toggle scope flags, set default, remove.</li>
 *   <li>Add currency — pick from the master registry.</li>
 *   <li>Exchange rates — manual entry plus history for any configured pair.</li>
 * </ul>
 */
@Component({
  selector: 'app-tenant-currencies-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent],
  templateUrl: './currencies-tab.component.html',
  styleUrl: './currencies-tab.component.scss',
})
export class TenantCurrenciesTabComponent implements OnInit {
  rows: CurrencyRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  pendingId: string | null = null;

  // Add-currency state
  available: Currency[] = [];
  selectedCode: string | null = null;
  newIsDefault = false;
  newIsBilling = true;
  newIsClaims = true;
  newIsPayment = true;
  adding = false;

  // Exchange-rates state
  rateBase: string = '';
  rateQuote: string = '';
  rateValue: string = '';
  rateDate: string = '';
  rateFrom: string = '';
  rateTo: string = '';
  rateHistory: ExchangeRate[] = [];
  ratesLoading = false;
  rateSaving = false;

  private masterByCode: Record<string, Currency> = {};

  constructor(
    private currencyService: CurrencyService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    const today = new Date().toISOString().slice(0, 10);
    const monthAgo = new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);
    this.rateDate = today;
    this.rateTo = today;
    this.rateFrom = monthAgo;
    this.refresh();
  }

  // ── Configured currencies ─────────────────────────────────────────────────

  refresh(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    this.loading = true;
    forkJoin({
      master: this.currencyService.listMaster(true),
      configs: this.currencyService.listForTenant(tenantId),
    }).subscribe({
      next: ({ master, configs }) => {
        this.masterByCode = Object.fromEntries(master.map(c => [c.code, c]));
        this.rows = configs.map(c => ({
          ...c,
          name: this.masterByCode[c.currencyCode]?.name,
          symbol: this.masterByCode[c.currencyCode]?.symbol,
        }));
        const taken = new Set(configs.map(c => c.currencyCode));
        this.available = master.filter(c => !taken.has(c.code));
        if (!this.rateBase) this.rateBase = configs[0]?.currencyCode ?? '';
        if (!this.rateQuote) this.rateQuote = configs[1]?.currencyCode ?? '';
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.message || 'Failed to load currencies';
        this.loading = false;
      },
    });
  }

  toggleFlag(row: CurrencyRow, flag: 'isBillingCurrency' | 'isClaimsCurrency' | 'isPaymentCurrency'): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.pendingId = row.id;
    this.currencyService.updateTenantCurrency(tenantId, row.id, { [flag]: !row[flag] }).subscribe({
      next: (updated) => {
        Object.assign(row, updated);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Update failed';
        this.pendingId = null;
      },
    });
  }

  setDefault(row: CurrencyRow): void {
    if (row.isDefault) return;
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.pendingId = row.id;
    this.currencyService.updateTenantCurrency(tenantId, row.id, { isDefault: true }).subscribe({
      next: () => {
        this.pendingId = null;
        this.refresh();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to set default';
        this.pendingId = null;
      },
    });
  }

  remove(row: CurrencyRow): void {
    if (row.isDefault) {
      this.errorMessage = 'Cannot remove the default currency. Promote another currency first.';
      return;
    }
    if (!confirm(`Remove ${row.currencyCode} from this tenant?`)) return;
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.pendingId = row.id;
    this.currencyService.removeFromTenant(tenantId, row.id).subscribe({
      next: () => {
        this.rows = this.rows.filter(r => r.id !== row.id);
        this.pendingId = null;
        this.refresh();
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to remove currency';
        this.pendingId = null;
      },
    });
  }

  // ── Add currency ──────────────────────────────────────────────────────────

  add(): void {
    if (!this.selectedCode) {
      this.errorMessage = 'Pick a currency to add';
      return;
    }
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.adding = true;
    this.errorMessage = null;
    this.currencyService.addToTenant(tenantId, {
      currencyCode: this.selectedCode,
      isDefault: this.newIsDefault,
      isBillingCurrency: this.newIsBilling,
      isClaimsCurrency: this.newIsClaims,
      isPaymentCurrency: this.newIsPayment,
    }).subscribe({
      next: () => {
        this.adding = false;
        this.successMessage = `Added ${this.selectedCode}`;
        this.selectedCode = null;
        this.newIsDefault = false;
        setTimeout(() => (this.successMessage = null), 3000);
        this.refresh();
      },
      error: (err) => {
        this.adding = false;
        this.errorMessage = err?.error?.detail || 'Failed to add currency';
      },
    });
  }

  // ── Exchange rates ────────────────────────────────────────────────────────

  loadRateHistory(): void {
    if (!this.rateBase || !this.rateQuote || !this.rateFrom || !this.rateTo) {
      this.errorMessage = 'Pick base, quote, and a date range';
      return;
    }
    if (this.rateBase === this.rateQuote) {
      this.errorMessage = 'Base and quote must differ';
      return;
    }
    this.ratesLoading = true;
    this.errorMessage = null;
    const tenantId = this.tenantService.getTenantId() || undefined;
    this.currencyService.rateHistory(this.rateBase, this.rateQuote, this.rateFrom, this.rateTo, tenantId).subscribe({
      next: (rates) => {
        this.rateHistory = rates;
        this.ratesLoading = false;
      },
      error: (err) => {
        this.ratesLoading = false;
        this.errorMessage = err?.error?.detail || 'Failed to load history';
      },
    });
  }

  recordRate(): void {
    if (!this.rateBase || !this.rateQuote || !this.rateValue || !this.rateDate) {
      this.errorMessage = 'Fill base, quote, rate, and date';
      return;
    }
    if (this.rateBase === this.rateQuote) {
      this.errorMessage = 'Base and quote must differ';
      return;
    }
    this.rateSaving = true;
    this.errorMessage = null;
    this.successMessage = null;
    const tenantId = this.tenantService.getTenantId() || undefined;
    this.currencyService.recordRate({
      baseCurrency: this.rateBase,
      quoteCurrency: this.rateQuote,
      rate: this.rateValue,
      rateDate: this.rateDate,
      source: 'manual',
      tenantId,
    }).subscribe({
      next: () => {
        this.rateSaving = false;
        this.rateValue = '';
        this.successMessage = `Rate recorded for ${this.rateBase} → ${this.rateQuote} on ${this.rateDate}`;
        setTimeout(() => (this.successMessage = null), 3000);
        this.loadRateHistory();
      },
      error: (err) => {
        this.rateSaving = false;
        this.errorMessage = err?.error?.detail || 'Failed to record rate';
      },
    });
  }
}
