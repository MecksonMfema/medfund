import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import {
  CtcAutoConfigService,
  TenantCtcAutoConfig,
  UpdateTenantCtcAutoConfigPayload,
} from '../../../../core/services/ctc-auto-config.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { PermissionService } from '../../../../core/security/permission.service';
import {
  CtcPaymentRow,
  FinanceService,
  FinancePageResponse,
} from '../../../../core/services/finance.service';

/**
 * Auto-CTC configuration + recent auto-drafts. Backed by
 * GET/PUT /api/v1/tenants/{id}/ctc-auto-config on tenancy-service (V129)
 * and GET /api/v1/ctc-payments/page?systemDrafted=true on finance-service.
 *
 * <p>Enabling here does not commit any drafts — it only turns on the
 * finance-side consumer's auto-draft branch. Every draft still requires
 * an operator to click Commit from the CTC list.
 */
@Component({
  selector: 'app-ctc-auto',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SkeletonComponent],
  templateUrl: './ctc-auto.component.html',
  styleUrl: './ctc-auto.component.scss',
})
export class CtcAutoComponent implements OnInit {
  loading = false;
  saving = false;
  loadingDrafts = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  draftsErrorMessage: string | null = null;

  config: TenantCtcAutoConfig | null = null;
  tenantCurrencies: TenantCurrencyConfig[] = [];
  recentDrafts: CtcPaymentRow[] = [];

  // Form model (kept separate from config so cancel is a re-hydrate away).
  enabled = false;
  minMemberBalanceThreshold = '0';
  maxPerCtcAmount: string | null = null;
  thresholdCurrency = 'USD';

  constructor(
    private ctcAutoConfigService: CtcAutoConfigService,
    private tenantService: TenantService,
    private currencyService: CurrencyService,
    private permissionService: PermissionService,
    private finance: FinanceService,
  ) {}

  ngOnInit(): void {
    this.refreshConfig();
    this.refreshTenantCurrencies();
    this.refreshRecentDrafts();
  }

  canConfigure(): boolean {
    return this.permissionService.has('finance:configure_auto_ctc');
  }

  refreshConfig(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.ctcAutoConfigService.get(tenantId).subscribe({
      next: config => {
        this.config = config;
        this.hydrateFormFrom(config);
        this.loading = false;
      },
      error: err => {
        this.errorMessage = extractError(err, 'Failed to load auto-CTC config');
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
      },
      error: () => {
        // Currency list failure is non-fatal — the input still accepts
        // arbitrary ISO codes, so surface only if the load-config itself
        // fails.
      },
    });
  }

  refreshRecentDrafts(): void {
    this.loadingDrafts = true;
    this.draftsErrorMessage = null;
    this.finance.listCtcPaymentsPaged({
      systemDrafted: true,
      sortKey: 'createdAt',
      sortDirection: 'desc',
      page: 0,
      size: 20,
    }).subscribe({
      next: (page: FinancePageResponse<CtcPaymentRow>) => {
        this.recentDrafts = page.content;
        this.loadingDrafts = false;
      },
      error: err => {
        this.draftsErrorMessage = extractError(err, 'Failed to load recent auto-drafts');
        this.loadingDrafts = false;
      },
    });
  }

  save(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    if (!this.canConfigure()) {
      this.errorMessage = 'You do not have permission to configure auto-CTC';
      return;
    }
    this.saving = true;
    this.errorMessage = null;
    this.successMessage = null;

    const payload: UpdateTenantCtcAutoConfigPayload = {
      enabled: this.enabled,
      minMemberBalanceThreshold: this.minMemberBalanceThreshold || '0',
      maxPerCtcAmount: this.maxPerCtcAmount && this.maxPerCtcAmount.length > 0
        ? this.maxPerCtcAmount : null,
      thresholdCurrency: (this.thresholdCurrency || 'USD').toUpperCase(),
    };

    this.ctcAutoConfigService.update(tenantId, payload).subscribe({
      next: config => {
        this.config = config;
        this.hydrateFormFrom(config);
        this.saving = false;
        this.successMessage = 'Auto-CTC config saved';
      },
      error: err => {
        this.errorMessage = extractError(err, 'Failed to save auto-CTC config');
        this.saving = false;
      },
    });
  }

  private hydrateFormFrom(config: TenantCtcAutoConfig): void {
    this.enabled = config.enabled;
    this.minMemberBalanceThreshold = config.minMemberBalanceThreshold ?? '0';
    this.maxPerCtcAmount = config.maxPerCtcAmount;
    this.thresholdCurrency = config.thresholdCurrency ?? 'USD';
  }
}

function extractError(err: unknown, fallback: string): string {
  if (err && typeof err === 'object' && 'error' in err) {
    const e = (err as { error?: { message?: string } }).error;
    if (e?.message) return e.message;
  }
  return fallback;
}
