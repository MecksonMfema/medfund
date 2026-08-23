import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { TenantService } from '../../../../core/services/tenant.service';
import {
  TenantAutoLapseConfig,
  TenantAutoLapseConfigService,
  UpdateTenantAutoLapseConfigPayload,
} from '../../../../core/services/tenant-auto-lapse-config.service';
import { PermissionService } from '../../../../core/security/permission.service';

/**
 * Toggle + threshold form for the auto-lapse chain (Phase 11 §B / P7).
 * Backed by GET/PUT /api/v1/tenants/{id}/auto-lapse-config (V133,
 * tenancy-service). When {@code enabled} is off, threshold + grace
 * are optional and the pipeline is dormant tenant-wide.
 */
@Component({
  selector: 'app-auto-lapse-config',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent],
  templateUrl: './auto-lapse-config.component.html',
  styleUrl: './auto-lapse-config.component.scss',
})
export class AutoLapseConfigComponent implements OnInit {
  loading = false;
  saving = false;
  saved = false;
  errorMessage: string | null = null;

  config: TenantAutoLapseConfig | null = null;

  enabled = false;
  arrearsThresholdMonths: number | null = 3;
  graceWindowDays: number | null = 7;

  constructor(
    private configService: TenantAutoLapseConfigService,
    private tenantService: TenantService,
    private permissionService: PermissionService,
  ) {}

  ngOnInit(): void {
    this.refresh();
  }

  canConfigure(): boolean {
    return this.permissionService.has('tenant.settings:manage_auto_lapse');
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
        this.errorMessage = extractError(err, 'Failed to load auto-lapse configuration');
        this.loading = false;
      },
    });
  }

  save(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    if (!this.canConfigure()) {
      this.errorMessage = 'You do not have permission to configure auto-lapse';
      return;
    }
    // When enabled, both fields are required. When disabled, the
    // server accepts nulls and the sweep is silent regardless.
    if (this.enabled) {
      if (this.arrearsThresholdMonths == null
          || this.arrearsThresholdMonths < 1 || this.arrearsThresholdMonths > 60) {
        this.errorMessage = 'Arrears threshold must be between 1 and 60 months.';
        return;
      }
      if (this.graceWindowDays == null
          || this.graceWindowDays < 0 || this.graceWindowDays > 180) {
        this.errorMessage = 'Grace window must be between 0 and 180 days.';
        return;
      }
    }
    this.saving = true;
    this.saved = false;
    this.errorMessage = null;

    const payload: UpdateTenantAutoLapseConfigPayload = {
      enabled: this.enabled,
      arrearsThresholdMonths: this.enabled ? this.arrearsThresholdMonths : this.arrearsThresholdMonths,
      graceWindowDays: this.enabled ? this.graceWindowDays : this.graceWindowDays,
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
        this.errorMessage = extractError(err, 'Failed to save auto-lapse configuration');
        this.saving = false;
      },
    });
  }

  private hydrateFormFrom(config: TenantAutoLapseConfig): void {
    this.enabled = !!config.enabled;
    this.arrearsThresholdMonths = config.arrearsThresholdMonths ?? 3;
    this.graceWindowDays = config.graceWindowDays ?? 7;
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
