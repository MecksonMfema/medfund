import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { TenantService } from '../../../../core/services/tenant.service';
import {
  TenantReportConfigRow,
  TenantReportConfigService,
} from '../../../../core/services/tenant-report-config.service';
import { HighCostClaimantConfigComponent } from './high-cost-claimant-config.component';

interface FamilyGroup {
  family: string;
  familyLabel: string;
  rows: TenantReportConfigRow[];
}

interface DiffEntry {
  reportKey: string;
  enabled: boolean;
}

/**
 * Bulk on/off grid for every catalogued report, grouped by family.
 * Ticks the boxes → hit Save → one PUT with the whole diff. Rows the
 * tenant admin never touched keep their default (enabled=true), so
 * newly-shipped reports appear without a settings sweep.
 */
@Component({
  selector: 'app-tenant-reports-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, HighCostClaimantConfigComponent],
  templateUrl: './reports-tab.component.html',
  styleUrl: './reports-tab.component.scss',
})
export class TenantReportsTabComponent implements OnInit {
  groups: FamilyGroup[] = [];
  loading = false;
  saving = false;
  saved = false;
  errorMessage: string | null = null;

  /** Snapshot of the loaded state used to compute the dirty-diff on save. */
  private originalEnabled = new Map<string, boolean>();
  /** Working state — one entry per reportKey. */
  currentEnabled = new Map<string, boolean>();
  /** Row lookup by reportKey — used for cascade-disable count. */
  private rowIndex = new Map<string, TenantReportConfigRow>();

  constructor(
    private reportConfig: TenantReportConfigService,
    private tenantService: TenantService,
    private router: Router,
    private confirmService: ConfirmService,
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
    this.reportConfig.invalidate(tenantId);
    this.reportConfig.list(tenantId).subscribe({
      next: (rows) => {
        this.originalEnabled = new Map(rows.map(r => [r.reportKey, r.enabled]));
        this.currentEnabled  = new Map(this.originalEnabled);
        this.rowIndex        = new Map(rows.map(r => [r.reportKey, r]));
        this.groups          = this.groupByFamily(rows);
        this.loading         = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Could not load report configuration.';
        this.loading      = false;
      },
    });
  }

  toggle(row: TenantReportConfigRow): void {
    const next = !this.currentEnabled.get(row.reportKey);
    this.currentEnabled.set(row.reportKey, next);
  }

  isEnabled(row: TenantReportConfigRow): boolean {
    return this.currentEnabled.get(row.reportKey) ?? true;
  }

  dirty(): boolean {
    for (const [k, v] of this.currentEnabled) {
      if (this.originalEnabled.get(k) !== v) return true;
    }
    return false;
  }

  save(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    const diff: DiffEntry[] = [];
    for (const [k, v] of this.currentEnabled) {
      if (this.originalEnabled.get(k) !== v) diff.push({ reportKey: k, enabled: v });
    }
    if (diff.length === 0) return;

    const disablingWithSchedules = diff
      .filter(d => !d.enabled)
      .map(d => this.rowIndex.get(d.reportKey))
      .filter((r): r is TenantReportConfigRow => !!r && (r.activeScheduleCount ?? 0) > 0);

    if (disablingWithSchedules.length > 0) {
      const total = disablingWithSchedules.reduce(
        (n, r) => n + (r.activeScheduleCount ?? 0), 0);
      const reportsLabel = disablingWithSchedules.length === 1 ? 'report' : 'reports';
      const schedulesLabel = total === 1 ? 'schedule' : 'schedules';
      const message =
        `Disabling ${disablingWithSchedules.length} ${reportsLabel} will pause ` +
        `${total} scheduled ${schedulesLabel}. Recipients will stop receiving these ` +
        `reports until the report is re-enabled. Continue?`;
      this.confirmService.ask({
        title: 'Pause scheduled deliveries?',
        message,
        confirmLabel: 'Disable & pause',
        cancelLabel: 'Keep enabled',
        danger: true,
      }).then(confirmed => {
        if (confirmed) this.doSave(tenantId, diff);
      });
      return;
    }
    this.doSave(tenantId, diff);
  }

  private doSave(tenantId: string, diff: DiffEntry[]): void {
    this.saving = true;
    this.saved  = false;
    this.errorMessage = null;
    this.reportConfig.bulkUpsert(tenantId, diff).subscribe({
      next: () => {
        this.originalEnabled = new Map(this.currentEnabled);
        this.saving = false;
        this.saved  = true;
        setTimeout(() => (this.saved = false), 3000);
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Could not save changes.';
        this.saving       = false;
      },
    });
  }

  toggleFamily(group: FamilyGroup, enable: boolean): void {
    for (const row of group.rows) {
      this.currentEnabled.set(row.reportKey, enable);
    }
  }

  /**
   * Deep-link to the report-schedules admin page anchored on this reportKey.
   * The schedules page reads the fragment on init and scrolls the matching
   * card into view.
   */
  manageSchedule(row: TenantReportConfigRow): void {
    this.router.navigate(
      ['/tenant/admin/settings/report-schedules'],
      { fragment: row.reportKey },
    );
  }

  private groupByFamily(rows: TenantReportConfigRow[]): FamilyGroup[] {
    const byKey = new Map<string, FamilyGroup>();
    for (const row of rows) {
      const familyKey = row.family ?? 'OTHER';
      const familyLabel = row.familyLabel ?? 'Other';
      let group = byKey.get(familyKey);
      if (!group) {
        group = { family: familyKey, familyLabel, rows: [] };
        byKey.set(familyKey, group);
      }
      group.rows.push(row);
    }
    return Array.from(byKey.values());
  }
}
