import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { TenantService } from '../../../../core/services/tenant.service';
import {
  SidebarToggleEntry,
  TenantSidebarConfigService,
  TenantSidebarSectionConfigRow,
} from '../../../../core/services/tenant-sidebar-config.service';

interface GroupBucket {
  group: string;
  groupLabel: string;
  rows: TenantSidebarSectionConfigRow[];
}

/**
 * Bulk on/off grid for every togglable operations-portal sidebar item,
 * grouped by nav-section. Tick the boxes -> hit Save -> one PUT with
 * only the changed rows. Rows the admin never touched keep their
 * default (enabled=true), so newly-shipped sidebar items appear
 * without a settings sweep.
 *
 * <p>Post-save re-emits the current tenant snapshot so the sidebar
 * rebuild picks up the new visibility without a page reload.
 */
@Component({
  selector: 'app-sidebar-sections-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent],
  templateUrl: './sidebar-sections-tab.component.html',
  styleUrl: './sidebar-sections-tab.component.scss',
})
export class SidebarSectionsTabComponent implements OnInit {
  buckets: GroupBucket[] = [];
  loading = false;
  saving = false;
  saved = false;
  errorMessage: string | null = null;

  /** Snapshot of loaded state used to compute the dirty-diff on save. */
  private originalEnabled = new Map<string, boolean>();
  /** Working state - one entry per sectionKey. */
  currentEnabled = new Map<string, boolean>();

  constructor(
    private sidebarConfig: TenantSidebarConfigService,
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
    this.sidebarConfig.invalidate(tenantId);
    this.sidebarConfig.list(tenantId).subscribe({
      next: rows => {
        this.originalEnabled = new Map(rows.map(r => [r.sectionKey, r.enabled]));
        this.currentEnabled  = new Map(this.originalEnabled);
        this.buckets         = this.groupByNavGroup(rows);
        this.loading         = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || 'Could not load sidebar visibility configuration.';
        this.loading      = false;
      },
    });
  }

  toggle(row: TenantSidebarSectionConfigRow): void {
    const next = !this.currentEnabled.get(row.sectionKey);
    this.currentEnabled.set(row.sectionKey, next);
  }

  isEnabled(row: TenantSidebarSectionConfigRow): boolean {
    return this.currentEnabled.get(row.sectionKey) ?? true;
  }

  dirty(): boolean {
    for (const [k, v] of this.currentEnabled) {
      if (this.originalEnabled.get(k) !== v) return true;
    }
    return false;
  }

  toggleGroup(bucket: GroupBucket, enable: boolean): void {
    for (const row of bucket.rows) {
      this.currentEnabled.set(row.sectionKey, enable);
    }
  }

  save(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    const diff: SidebarToggleEntry[] = [];
    for (const [k, v] of this.currentEnabled) {
      if (this.originalEnabled.get(k) !== v) diff.push({ sectionKey: k, enabled: v });
    }
    if (diff.length === 0) return;

    this.saving = true;
    this.saved  = false;
    this.errorMessage = null;
    this.sidebarConfig.bulkUpsert(tenantId, diff).subscribe({
      next: () => {
        this.originalEnabled = new Map(this.currentEnabled);
        this.saving = false;
        this.saved  = true;
        // Re-emit the current tenant so subscribers like the operational
        // sidebar re-hydrate the disabled-section set and rebuild the nav
        // without a page reload.
        const current = this.tenantService.getTenant();
        if (current) this.tenantService.setTenant({ ...current });
        setTimeout(() => (this.saved = false), 3000);
      },
      error: err => {
        this.errorMessage = err?.error?.detail || 'Could not save changes.';
        this.saving       = false;
      },
    });
  }

  private groupByNavGroup(rows: TenantSidebarSectionConfigRow[]): GroupBucket[] {
    const byKey = new Map<string, GroupBucket>();
    for (const row of rows) {
      const groupKey = row.group ?? 'OTHER';
      const groupLabel = row.groupLabel ?? 'Other';
      let bucket = byKey.get(groupKey);
      if (!bucket) {
        bucket = { group: groupKey, groupLabel, rows: [] };
        byKey.set(groupKey, bucket);
      }
      bucket.rows.push(row);
    }
    return Array.from(byKey.values());
  }
}
