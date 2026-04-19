import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Color } from '@swimlane/ngx-charts';
import { OCEAN_BREEZE_SCHEME } from '../../shared/components/charts/chart-colors';
import { AreaChartComponent } from '../../shared/components/charts/area-chart/area-chart.component';
import { StatCardComponent } from '../../shared/components/stat-card/stat-card.component';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { TenantService } from '../../core/services/tenant.service';
import { AdminService } from '../../core/services/admin.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, StatCardComponent, AreaChartComponent, IconComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit {
  tenantName = '';
  logoUrl = '';
  tenantInitial = 'T';
  hasTenantContext = false;

  // ── User stats ─────────────────────────────────────────────────────────────
  totalUsers = 0;
  activeUsers = 0;
  suspendedUsers = 0;
  pendingUsers = 0;

  // ── System activity chart ──────────────────────────────────────────────────
  activityData: any[] = [];
  /** Every 5th date label — prevents crowding on the 30-day x-axis. */
  activityTicks: string[] = [];
  colorScheme: Color = OCEAN_BREEZE_SCHEME;

  // ── Recent audit log ───────────────────────────────────────────────────────
  recentAudit: any[] = [];
  auditLoading = true;

  constructor(
    private tenantService: TenantService,
    private adminService: AdminService,
  ) {}

  ngOnInit(): void {
    const tenant = this.tenantService.getTenant();
    if (tenant) {
      this.tenantName       = tenant.name;
      this.logoUrl          = tenant.branding?.logoUrl ?? '';
      this.tenantInitial    = tenant.name?.[0]?.toUpperCase() ?? 'T';
      this.hasTenantContext = !!tenant.id;
    }

    this.loadUsers();
    this.loadAuditData();
  }

  private loadUsers(): void {
    this.adminService.getStaffUsers().subscribe({
      next: (users) => {
        this.totalUsers     = users.length;
        this.activeUsers    = users.filter(u => u.status === 'active').length;
        this.suspendedUsers = users.filter(u => u.status === 'suspended').length;
        this.pendingUsers   = users.filter(u =>
          u.status === 'invited' || u.status === 'pending'
        ).length;
      },
      error: () => {},
    });
  }

  /**
   * Loads up to 200 recent audit events for this tenant, then builds:
   *  - a 30-day activity line chart (events per day)
   *  - the "Recent Activity" list (last 6)
   */
  private loadAuditData(): void {
    this.auditLoading = true;
    const tenantId = this.tenantService.getTenantId();

    if (!tenantId) {
      this.auditLoading = false;
      this.recentAudit  = [];
      const empty = this.buildActivitySeries([]);
      this.activityData  = empty.series;
      this.activityTicks = empty.ticks;
      return;
    }

    this.adminService.getAuditEvents({ page: '1', size: '200' }, tenantId).subscribe({
      next: (a) => {
        const events = a.events || [];

        // Recent list — 5 most recent (API returns newest-first)
        this.recentAudit = events.slice(0, 5).map((e: any) => ({
          ...e,
          _display: e.entityName || e.entityId,
          _actor:   e.actorEmail || e.actorId || '—',
        }));

        // Chart — events per day over 30 days
        const result = this.buildActivitySeries(events);
        this.activityData  = result.series;
        this.activityTicks = result.ticks;
        this.auditLoading = false;
      },
      error: () => {
        this.auditLoading = false;
        const fallback = this.buildActivitySeries([]);
        this.activityData  = fallback.series;
        this.activityTicks = fallback.ticks;
      },
    });
  }

  private buildActivitySeries(events: any[]): { series: any[]; ticks: string[] } {
    const days = 30;
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const counts: Record<string, number> = {};
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date(today);
      d.setDate(d.getDate() - i);
      counts[this.dateKey(d)] = 0;
    }

    for (const e of events) {
      if (!e.timestamp) continue;
      const key = this.dateKey(new Date(e.timestamp));
      if (key in counts) counts[key]++;
    }

    const series = Object.entries(counts).map(([k, v]) => ({
      name: this.shortDate(new Date(k)),
      value: v,
    }));

    // Show every 5th date label to prevent x-axis crowding on the 30-day chart
    const ticks = series.filter((_, i) => i % 5 === 0 || i === series.length - 1).map(p => p.name);

    return { series: [{ name: 'System Events', series }], ticks };
  }

  /** Returns the display value, replacing bare UUIDs with '—' (they carry no meaning to users). */
  formatDisplay(value: string | undefined | null): string {
    if (!value) return '—';
    const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (uuidPattern.test(value.trim())) return '—';
    return value;
  }

  private dateKey(d: Date): string {
    return d.toISOString().slice(0, 10);
  }

  private shortDate(d: Date): string {
    return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
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
