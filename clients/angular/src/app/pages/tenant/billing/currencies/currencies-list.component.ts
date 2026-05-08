import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  CurrencyService,
  Currency,
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
 * Lists the currencies configured for the current tenant. Each row exposes
 * scope-flag toggles, a "set as default" action, and a remove action — all
 * applied via the per-row controls so the page never refreshes.
 */
@Component({
  selector: 'app-tenant-currencies-list',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, SkeletonComponent],
  templateUrl: './currencies-list.component.html',
  styleUrl: './currencies-list.component.scss',
})
export class TenantCurrenciesListComponent implements OnInit {
  rows: CurrencyRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  pendingId: string | null = null;

  private masterByCode: Record<string, Currency> = {};

  constructor(
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.refresh();
  }

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
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to remove currency';
        this.pendingId = null;
      },
    });
  }

  goToRates(): void {
    this.router.navigate(['/tenant/billing/currencies/rates']);
  }
}
