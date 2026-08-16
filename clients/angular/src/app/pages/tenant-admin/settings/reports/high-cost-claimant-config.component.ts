import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { TenantService } from '../../../../core/services/tenant.service';
import {
  HighCostClaimantConfigService,
  TenantHighCostClaimantConfig,
  UpdateTenantHighCostClaimantConfigPayload,
} from '../../../../core/services/high-cost-claimant-config.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { PermissionService } from '../../../../core/security/permission.service';

/**
 * Threshold form for the HIGH_COST_CLAIMANT report, co-located in the
 * tenant-admin Reports settings tab. Wraps GET/PUT
 * /api/v1/tenants/{id}/high-cost-claimant-config (tenancy-service, V132).
 * The threshold is stored in its own native currency and converted to the
 * reporting currency at report time — the picker follows the currencies-tab
 * tenant-currency pattern.
 */
@Component({
  selector: 'app-high-cost-claimant-config',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent],
  templateUrl: './high-cost-claimant-config.component.html',
  styleUrl: './high-cost-claimant-config.component.scss',
})
export class HighCostClaimantConfigComponent implements OnInit {
  loading = false;
  saving = false;
  saved = false;
  errorMessage: string | null = null;

  config: TenantHighCostClaimantConfig | null = null;
  tenantCurrencies: TenantCurrencyConfig[] = [];

  thresholdAmount = '';
  currencyCode = 'USD';

  constructor(
    private configService: HighCostClaimantConfigService,
    private tenantService: TenantService,
    private currencyService: CurrencyService,
    private permissionService: PermissionService,
  ) {}

  ngOnInit(): void {
    this.refresh();
    this.refreshTenantCurrencies();
  }

  canConfigure(): boolean {
    return this.permissionService.has('admin:manage_settings');
  }

  refresh(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.configService.get(tenantId).subscribe({
      next: config => {
        this.config = config;
        this.hydrateFormFrom(config);
        this.loading = false;
      },
      error: err => {
        this.errorMessage = extractError(err, 'Failed to load high-cost claimant threshold');
        this.loading = false;
      },
    });
  }

  refreshTenantCurrencies(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.currencyService.listForTenant(tenantId).subscribe({
      next: currencies => {
        this.tenantCurrencies = currencies.filter(c => c.isActive);
        const def = this.tenantCurrencies.find(c => c.isDefault);
        if (def && this.currencyCode === 'USD' && this.thresholdAmount === '') {
          this.currencyCode = def.currencyCode;
        }
      },
      error: () => {
        // Currency list failure is non-fatal — the select still accepts
        // the stored code, and the API validates ISO-4217 shape.
      },
    });
  }

  save(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    if (!this.canConfigure()) {
      this.errorMessage = 'You do not have permission to configure the high-cost threshold';
      return;
    }
    const amount = this.thresholdAmount.trim();
    if (amount === '' || Number.isNaN(Number(amount)) || Number(amount) < 0) {
      this.errorMessage = 'Enter a non-negative threshold amount.';
      return;
    }
    this.saving = true;
    this.saved = false;
    this.errorMessage = null;

    const payload: UpdateTenantHighCostClaimantConfigPayload = {
      thresholdAmount: amount,
      currencyCode: (this.currencyCode || 'USD').toUpperCase(),
    };

    this.configService.update(tenantId, payload).subscribe({
      next: config => {
        this.config = config;
        this.hydrateFormFrom(config);
        this.saving = false;
        this.saved = true;
        setTimeout(() => (this.saved = false), 3000);
      },
      error: err => {
        this.errorMessage = extractError(err, 'Failed to save high-cost claimant threshold');
        this.saving = false;
      },
    });
  }

  private hydrateFormFrom(config: TenantHighCostClaimantConfig): void {
    this.thresholdAmount = config.thresholdAmount ?? '';
    this.currencyCode = config.currencyCode ?? 'USD';
  }
}

function extractError(err: unknown, fallback: string): string {
  if (err && typeof err === 'object' && 'error' in err) {
    const e = (err as { error?: { message?: string; detail?: string } }).error;
    if (e?.message) return e.message;
    if (e?.detail) return e.detail;
  }
  return fallback;
}
