import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  CurrencyService,
  ExchangeRate,
  TenantCurrencyConfig,
} from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-exchange-rates',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './exchange-rates.component.html',
  styleUrl: './exchange-rates.component.scss',
})
export class ExchangeRatesComponent implements OnInit {
  configs: TenantCurrencyConfig[] = [];
  base: string = '';
  quote: string = '';
  from: string = '';
  to: string = '';
  rate: string = '';
  rateDate: string = '';
  history: ExchangeRate[] = [];
  loading = false;
  saving = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  constructor(
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
        this.configs = configs.filter(c => c.isActive);
        const today = new Date().toISOString().slice(0, 10);
        const monthAgo = new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);
        this.rateDate = today;
        this.to = today;
        this.from = monthAgo;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load tenant currencies';
      },
    });
  }

  loadHistory(): void {
    if (!this.base || !this.quote || !this.from || !this.to) {
      this.errorMessage = 'Pick base, quote, and a date range';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    const tenantId = this.tenantService.getTenantId() || undefined;
    this.currencyService.rateHistory(this.base, this.quote, this.from, this.to, tenantId).subscribe({
      next: (rates) => {
        this.history = rates;
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.detail || 'Failed to load history';
      },
    });
  }

  recordRate(): void {
    if (!this.base || !this.quote || !this.rate || !this.rateDate) {
      this.errorMessage = 'Fill base, quote, rate, and date';
      return;
    }
    if (this.base === this.quote) {
      this.errorMessage = 'Base and quote must differ';
      return;
    }
    this.saving = true;
    this.errorMessage = null;
    this.successMessage = null;
    const tenantId = this.tenantService.getTenantId() || undefined;
    this.currencyService.recordRate({
      baseCurrency: this.base,
      quoteCurrency: this.quote,
      rate: this.rate,
      rateDate: this.rateDate,
      source: 'manual',
      tenantId,
    }).subscribe({
      next: () => {
        this.saving = false;
        this.rate = '';
        this.successMessage = `Rate recorded for ${this.base} → ${this.quote} on ${this.rateDate}`;
        this.loadHistory();
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || 'Failed to record rate';
      },
    });
  }
}
