import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { TenantService } from '../../../../core/services/tenant.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import {
  TenantEndorsementConfig,
  TenantEndorsementConfigService,
  UpdateTenantEndorsementConfigPayload,
} from '../../../../core/services/tenant-endorsement-config.service';
import { PermissionService } from '../../../../core/security/permission.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../core/util/http-errors';

/**
 * Toggle + threshold form for the endorsement four-eyes gate (Phase 12
 * §C). Backed by GET/PUT /api/v1/tenants/{id}/endorsement-config (V134,
 * tenancy-service). When {@code enabled} is off, threshold + currency
 * are nulled server-side and the user-service auto-commits every
 * endorsement regardless of amount.
 */
@Component({
  selector: 'app-endorsement-config',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, SelectComponent],
  templateUrl: './endorsement-config.component.html',
  styleUrl: './endorsement-config.component.scss',
})
export class EndorsementConfigComponent implements OnInit {
  loading = false;
  saving = false;
  saved = false;

  config: TenantEndorsementConfig | null = null;

  enabled = false;
  fourEyesThresholdAmount: number | null = null;
  thresholdCurrency = '';

  currencies: TenantCurrencyConfig[] = [];

  constructor(
    private configService: TenantEndorsementConfigService,
    private tenantService: TenantService,
    private currencyService: CurrencyService,
    private permissionService: PermissionService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.loadCurrencies();
    this.refresh();
  }

  canConfigure(): boolean {
    return this.permissionService.has('tenant.settings:manage_endorsement_config');
  }

  refresh(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.toast.warning('No active tenant context');
      return;
    }
    this.loading = true;
    this.configService.get(tenantId).subscribe({
      next: config => {
        this.config = config;
        this.hydrateFormFrom(config);
        this.loading = false;
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to load endorsement configuration'));
        this.loading = false;
      },
    });
  }

  save(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    if (!this.canConfigure()) {
      this.toast.warning('You do not have permission to configure endorsements');
      return;
    }
    // When enabled, both threshold + currency are required. When
    // disabled, the server nulls both back out - see the
    // TenantEndorsementConfigService.upsert_disabledClearsThreshold
    // Deviation in the plan.
    if (this.enabled) {
      if (this.fourEyesThresholdAmount == null || this.fourEyesThresholdAmount < 0) {
        this.toast.warning('Threshold amount must be zero or greater.');
        return;
      }
      if (!this.thresholdCurrency || !/^[A-Z]{3}$/.test(this.thresholdCurrency)) {
        this.toast.warning('Threshold currency is required (ISO-4217 3-letter code).');
        return;
      }
    }
    this.saving = true;
    this.saved = false;

    const payload: UpdateTenantEndorsementConfigPayload = {
      enabled: this.enabled,
      fourEyesThresholdAmount: this.enabled ? this.fourEyesThresholdAmount : null,
      thresholdCurrency:       this.enabled ? this.thresholdCurrency        : null,
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
        this.toast.error(extractErrorMessage(err, 'Failed to save endorsement configuration'));
        this.saving = false;
      },
    });
  }

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: '- Select currency -' },
      ...this.currencies.map(c => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  private loadCurrencies(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.currencyService.listForTenant(tenantId).subscribe({
      next: cs => { this.currencies = cs.filter(c => c.isActive); },
      error: () => { /* non-fatal: falls back to free text via [ngModel] */ },
    });
  }

  private hydrateFormFrom(config: TenantEndorsementConfig): void {
    this.enabled = !!config.enabled;
    // Server sends BigDecimal as a string; parse for the number input.
    this.fourEyesThresholdAmount = config.fourEyesThresholdAmount != null
      ? Number(config.fourEyesThresholdAmount)
      : null;
    this.thresholdCurrency = config.thresholdCurrency ?? '';
  }
}
