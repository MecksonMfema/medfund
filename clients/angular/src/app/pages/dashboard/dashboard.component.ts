import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Subject } from 'rxjs';
import { distinctUntilChanged, takeUntil } from 'rxjs/operators';
import { AreaChartComponent } from '../../shared/components/charts/area-chart/area-chart.component';
import { StatCardComponent } from '../../shared/components/stat-card/stat-card.component';
import { TenantService } from '../../core/services/tenant.service';
import { AdminService } from '../../core/services/admin.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, StatCardComponent, AreaChartComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit, OnDestroy {
  tenantName = '';
  logoUrl = '';
  tenantInitial = 'T';
  hasTenantContext = false;

  // ── User stats ─────────────────────────────────────────────────────────────
  totalUsers = 0;
  activeUsers = 0;
  suspendedUsers = 0;
  pendingUsers = 0;

  private destroy$ = new Subject<void>();

  // ── System activity chart ──────────────────────────────────────────────────
  activityData: any[] = [];
  activityMax = 5;

  // ── Recent audit log ───────────────────────────────────────────────────────
  recentAudit: any[] = [];
  auditLoading = true;

  constructor(
    private tenantService: TenantService,
    private adminService: AdminService,
  ) {}

  ngOnInit(): void {
    // React to tenant changes — fires immediately from BehaviorSubject if already set,
    // fires later if the layout bootstraps it asynchronously (direct login).
    // distinctUntilChanged prevents re-loading on branding-only updates.
    this.tenantService.tenant$.pipe(
      distinctUntilChanged((a, b) => a?.id === b?.id),
      takeUntil(this.destroy$),
    ).subscribe(tenant => {
      if (tenant?.id) {
        this.tenantName       = tenant.name;
        this.logoUrl          = tenant.branding?.logoUrl ?? '';
        this.tenantInitial    = tenant.name?.[0]?.toUpperCase() ?? 'T';
        this.hasTenantContext = true;
        this.loadStats();
        this.loadAuditData();
      } else {
        this.hasTenantContext = false;
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private loadStats(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.adminService.getTenantStats(tenantId).subscribe({
      next: (s) => {
        this.totalUsers     = s.totalStaff;
        this.activeUsers    = s.activeStaff;
        this.suspendedUsers = s.suspendedStaff;
        this.pendingUsers   = s.pendingStaff;
      },
      error: () => {},
    });
  }

  private loadAuditData(): void {
    this.auditLoading = true;
    const tenantId = this.tenantService.getTenantId();

    if (!tenantId) {
      this.auditLoading = false;
      return;
    }

    // Fetch recent events — interceptor adds X-Tenant-ID automatically
    this.adminService.getAuditEvents({ page: '1', size: '6' }, tenantId).subscribe({
      next: (a) => {
        this.recentAudit = (a.events || []).map((e: any) => ({
          ...e,
          _display: e.entityName || e.entityId,
          _actor:   e.actorEmail || e.actorId || '—',
        }));
      },
      error: () => { this.recentAudit = []; },
    });

    // Pre-generate 30 date labels (same pattern as analytics axisLabels).
    // The labels exist immediately so the chart always renders with an x-axis.
    const labels = this.last30DayLabels();
    const emptySeries = labels.map(name => ({ name, value: 0 }));
    this.activityData = [{ name: 'Events', series: emptySeries }];

    // Fetch pre-aggregated daily counts — interceptor adds X-Tenant-ID automatically
    this.adminService.getAuditDailyCounts(30).subscribe({
      next: (counts) => {
        const series = labels.map((label, i) => ({
          name:  label,
          value: counts[i]?.count ?? 0,
        }));
        this.activityData = [{ name: 'Events', series }];
        const max = Math.max(...series.map(s => s.value));
        this.activityMax  = max > 0 ? Math.ceil(max * 1.2) : 5;
        this.auditLoading = false;
      },
      error: () => { this.auditLoading = false; },
    });
  }

  /** Returns the display value, replacing bare UUIDs with '—'. */
  formatDisplay(value: string | undefined | null): string {
    if (!value) return '—';
    const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (uuidPattern.test(value.trim())) return '—';
    return value;
  }

  /** 10 evenly spaced date labels over the last 30 days — same pattern as analytics axisLabels(). */
  private last30DayLabels(): string[] {
    const today = new Date();
    return Array.from({ length: 10 }, (_, i) => {
      const daysAgo = Math.round(29 * (1 - i / 9));
      const d = new Date(today);
      d.setDate(today.getDate() - daysAgo);
      return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
    });
  }

  private shortDate(isoDate: string): string {
    return new Date(isoDate + 'T00:00:00Z').toLocaleDateString('en-GB', {
      day: 'numeric', month: 'short', timeZone: 'UTC',
    });
  }

  actionLabel(action: string): string {
    const map: Record<string, string> = {
      CREATE: 'Created', UPDATE: 'Updated', DELETE: 'Deleted',
      LOGIN: 'Logged in', LOGOUT: 'Logged out', LOGIN_ERROR: 'Failed login',
    };
    return map[action] ?? action;
  }

  actionColor(action: string): string {
    const map: Record<string, string> = {
      CREATE: 'green', UPDATE: 'blue', DELETE: 'red',
      LOGIN: 'teal', LOGOUT: 'gray', LOGIN_ERROR: 'orange',
    };
    return map[action] ?? 'gray';
  }
}
