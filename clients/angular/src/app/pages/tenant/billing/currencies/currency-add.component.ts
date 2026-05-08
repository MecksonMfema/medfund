import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import {
  CurrencyService,
  Currency,
  TenantCurrencyConfig,
} from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-tenant-currency-add',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './currency-add.component.html',
  styleUrl: './currency-add.component.scss',
})
export class TenantCurrencyAddComponent implements OnInit {
  available: Currency[] = [];
  selected: string | null = null;
  isDefault = false;
  isBillingCurrency = true;
  isClaimsCurrency = true;
  isPaymentCurrency = true;
  saving = false;
  errorMessage: string | null = null;

  constructor(
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    forkJoin({
      master: this.currencyService.listMaster(true),
      configs: this.currencyService.listForTenant(tenantId),
    }).subscribe({
      next: ({ master, configs }) => {
        const taken = new Set(configs.map(c => c.currencyCode));
        this.available = master.filter(c => !taken.has(c.code));
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load currencies';
      },
    });
  }

  submit(): void {
    if (!this.selected) {
      this.errorMessage = 'Pick a currency to add';
      return;
    }
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.saving = true;
    this.errorMessage = null;
    this.currencyService.addToTenant(tenantId, {
      currencyCode: this.selected,
      isDefault: this.isDefault,
      isBillingCurrency: this.isBillingCurrency,
      isClaimsCurrency: this.isClaimsCurrency,
      isPaymentCurrency: this.isPaymentCurrency,
    }).subscribe({
      next: () => {
        this.saving = false;
        this.router.navigate(['/tenant/billing/currencies']);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || 'Failed to add currency';
      },
    });
  }
}
