import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  DirectionalStrategy,
  ProrationService,
  ProrationStrategy,
  TenantProrationConfig,
  UpdateTenantProrationConfigPayload,
} from '../../../../core/services/proration.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

interface StrategyOption {
  value: ProrationStrategy;
  label: string;
  description: string;
}

/**
 * Tenant benefit-limit proration configuration — embedded as a tab inside the
 * IT-admin Settings page. Backed by GET/PUT /api/v1/tenants/{id}/proration-config.
 *
 * <p>Absent config → server returns the platform default (NONE, all overrides
 * null). The form pre-fills from that default so first-time save is a single
 * click. HYBRID_BY_DIRECTION toggles the direction-override rows.
 */
@Component({
  selector: 'app-tenant-proration-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, SelectComponent],
  templateUrl: './proration-tab.component.html',
  styleUrl: './proration-tab.component.scss',
})
export class TenantProrationTabComponent implements OnInit {
  loading = false;
  saving = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  config: TenantProrationConfig | null = null;

  // Form model (kept separate from `config` so the user can cancel without a refetch).
  defaultStrategy: ProrationStrategy = 'NONE';
  upgradeStrategy: DirectionalStrategy | '' = '';
  downgradeStrategy: DirectionalStrategy | '' = '';
  currencyStrategy: DirectionalStrategy | '' = '';
  incrementWaitDays: number | null = null;

  readonly strategyOptions: StrategyOption[] = [
    { value: 'NONE',                        label: 'NONE',                        description: 'No proration — use the new scheme’s raw annual limit. Preserves pre-feature behaviour.' },
    { value: 'DELTA_CREDIT',                label: 'DELTA_CREDIT',                description: 'newLimit − consumedAcrossAnyScheme, floored at 0. Simplest safe correction.' },
    { value: 'RATIO_CARRY',                 label: 'RATIO_CARRY',                 description: '(oldRemaining / oldLimit) × newLimit. MASCA-style transfer of the spending ratio.' },
    { value: 'CALENDAR',                    label: 'CALENDAR',                    description: 'newLimit × (daysRemainingInYear / daysInYear) − consumedUnderNewScheme.' },
    { value: 'SPLIT_YEAR',                  label: 'SPLIT_YEAR',                  description: 'Two budgets on either side of the effective date. Same math as CALENDAR for the new-scheme bucket.' },
    { value: 'WAITING_PERIOD_ON_INCREMENT', label: 'WAITING_PERIOD_ON_INCREMENT', description: 'The increment unlocks only after N days from the change (see wait-days field).' },
    { value: 'HYBRID_BY_DIRECTION',         label: 'HYBRID_BY_DIRECTION',         description: 'Use different strategies for upgrade / downgrade / currency-change. Configure below.' },
  ];

  constructor(
    private prorationService: ProrationService,
    private tenantService: TenantService,
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
    this.errorMessage = null;
    this.prorationService.get(tenantId).subscribe({
      next: config => {
        this.config = config;
        this.hydrateFormFrom(config);
        this.loading = false;
      },
      error: err => {
        this.errorMessage = extractError(err);
        this.loading = false;
      },
    });
  }

  save(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.saving = true;
    this.errorMessage = null;
    this.successMessage = null;

    // Direction overrides only ship when the parent strategy is HYBRID_BY_DIRECTION,
    // otherwise the server may keep values but they're ignored. Send nulls to
    // clear them explicitly when switching away.
    const isHybrid = this.defaultStrategy === 'HYBRID_BY_DIRECTION';
    const payload: UpdateTenantProrationConfigPayload = {
      defaultStrategy: this.defaultStrategy,
      upgradeStrategy: isHybrid ? (this.upgradeStrategy || null) : null,
      downgradeStrategy: isHybrid ? (this.downgradeStrategy || null) : null,
      currencyStrategy: isHybrid ? (this.currencyStrategy || null) : null,
      incrementWaitDays: this.needsWaitDays() ? this.incrementWaitDays : null,
    };

    this.prorationService.update(tenantId, payload).subscribe({
      next: config => {
        this.config = config;
        this.hydrateFormFrom(config);
        this.saving = false;
        this.successMessage = 'Proration config saved';
      },
      error: err => {
        this.errorMessage = extractError(err);
        this.saving = false;
      },
    });
  }

  // ── Derived options ─────────────────────────────────────────────────────

  get defaultStrategyOptions(): SelectOption[] {
    return this.strategyOptions.map(o => ({
      value: o.value,
      label: o.label,
      description: o.description,
    }));
  }

  /** Direction-override lists exclude HYBRID (a leaf strategy is required). */
  get directionalStrategyOptions(): SelectOption[] {
    return this.strategyOptions
      .filter(o => o.value !== 'HYBRID_BY_DIRECTION')
      .map(o => ({ value: o.value, label: o.label, description: o.description }));
  }

  isHybrid(): boolean {
    return this.defaultStrategy === 'HYBRID_BY_DIRECTION';
  }

  needsWaitDays(): boolean {
    if (this.defaultStrategy === 'WAITING_PERIOD_ON_INCREMENT') return true;
    return this.isHybrid() && (
      this.upgradeStrategy === 'WAITING_PERIOD_ON_INCREMENT' ||
      this.downgradeStrategy === 'WAITING_PERIOD_ON_INCREMENT' ||
      this.currencyStrategy === 'WAITING_PERIOD_ON_INCREMENT');
  }

  // ── Helpers ─────────────────────────────────────────────────────────────

  private hydrateFormFrom(config: TenantProrationConfig): void {
    this.defaultStrategy = config.defaultStrategy;
    this.upgradeStrategy = (config.upgradeStrategy ?? '') as DirectionalStrategy | '';
    this.downgradeStrategy = (config.downgradeStrategy ?? '') as DirectionalStrategy | '';
    this.currencyStrategy = (config.currencyStrategy ?? '') as DirectionalStrategy | '';
    this.incrementWaitDays = config.incrementWaitDays;
  }
}

function extractError(err: unknown): string {
  if (err && typeof err === 'object' && 'error' in err) {
    const e = (err as { error?: { message?: string } }).error;
    if (e?.message) return e.message;
  }
  return 'Failed to update proration config';
}
