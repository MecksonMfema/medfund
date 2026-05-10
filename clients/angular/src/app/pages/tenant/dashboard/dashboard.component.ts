import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin, of, Subscription } from 'rxjs';
import { catchError, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { HasPermissionDirective } from '../../../shared/directives/has-permission.directive';
import { AreaChartComponent } from '../../../shared/components/charts/area-chart/area-chart.component';
import { PermissionKey } from '../../../core/security/permissions';
import { PermissionService } from '../../../core/security/permission.service';
import { TenantService } from '../../../core/services/tenant.service';
import { AdminService, TenantStats, TenantCharts, TrendPoint } from '../../../core/services/admin.service';

const EMPTY_STATS: TenantStats = {
  totalStaff: 0, activeStaff: 0, suspendedStaff: 0, pendingStaff: 0,
  totalMembers: 0, activeMembers: 0, enrolledMembers: 0,
  newMembersThisMonth: 0, newGroupsThisMonth: 0,
  claimsNewTasks: 0, claimsThisMonth: 0,
  claimsAcceptedThisMonth: 0, claimsRejectedThisMonth: 0,
  schemesActive: 0, contributionsPending: 0,
  contributionsAmountThisMonth: 0, contributionsAmountThisYear: 0,
  paymentsPending: 0, paymentsAmountThisMonth: 0, paymentsAmountThisYear: 0,
};

type DashboardTab = 'claims' | 'billing' | 'finance' | 'members';

interface TabDef {
  id: DashboardTab;
  label: string;
  permission: PermissionKey;
}

/**
 * Operational dashboard — surfaces the tenant's at-a-glance stats grouped by
 * domain in a tabbed layout. Each tab (and its contents) is gated by the
 * matching `*:view` permission, so a finance-only user sees only the Finance
 * tab; a tenant admin sees all four. The tab strip auto-selects the first
 * tab the user has permission for, so there's no flash of empty state.
 *
 * Charts and stat-card labels mirror the legacy MASCA dashboards
 * (Masca-Claims-Admin, Masca-Finance-Typescript, MASCA-Frontend) — see the
 * inline comments next to each tab.
 */
@Component({
  selector: 'app-tenant-operational-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, HasPermissionDirective, AreaChartComponent],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class TenantOperationalDashboardComponent implements OnInit, OnDestroy {
  tenantName = '';
  schemeLabelPlural = 'Schemes';
  loading = false;
  loadError: string | null = null;
  stats: TenantStats = { ...EMPTY_STATS };

  /** ngx-charts series envelopes — one series each, populated from {@link TenantCharts}. */
  claimsSeries:        Array<{ name: string; series: TrendPoint[] }> = [];
  contributionsSeries: Array<{ name: string; series: TrendPoint[] }> = [];
  paymentsSeries:      Array<{ name: string; series: TrendPoint[] }> = [];
  /** Y-axis hint so the area chart renders meaningfully when values are zero or tiny. */
  claimsMax = 5;
  contributionsMax = 1000;
  paymentsMax = 1000;

  /** True once the permission set has finished its first load — gates the empty state. */
  permissionsResolved = false;
  hasAnyView = false;

  /** Which domain tab is currently active. {@code null} until permissions resolve. */
  activeTab: DashboardTab | null = null;

  /**
   * The four tabs in display order. {@link permission} drives both the
   * `*hasPermission` gate on the tab button and the auto-select fallback.
   */
  readonly tabs: ReadonlyArray<TabDef> = [
    { id: 'claims',  label: 'Claims',   permission: 'claims:view'   as PermissionKey },
    { id: 'billing', label: 'Billing',  permission: 'billing:view'  as PermissionKey },
    { id: 'finance', label: 'Finance',  permission: 'finance:view'  as PermissionKey },
    { id: 'members', label: 'Members',  permission: 'members:view'  as PermissionKey },
  ];

  private subs: Subscription[] = [];

  constructor(
    private permissions: PermissionService,
    private tenantService: TenantService,
    private adminService: AdminService,
  ) {}

  ngOnInit(): void {
    this.subs.push(this.tenantService.tenant$.subscribe(t => {
      this.tenantName        = t?.name              ?? '';
      this.schemeLabelPlural = t?.schemeLabelPlural ?? 'Schemes';
    }));

    // Refetch stats + 12-month chart data whenever the active tenant changes.
    // forkJoin runs both in parallel; if charts fail we keep the stats and
    // render charts as empty series rather than failing the whole dashboard.
    this.subs.push(this.tenantService.tenant$.pipe(
      distinctUntilChanged((a, b) => a?.id === b?.id),
      switchMap(t => {
        if (!t?.id) return of(null);
        this.loading = true;
        this.loadError = null;
        return forkJoin({
          stats:  this.adminService.getTenantStats(t.id),
          charts: this.adminService.getTenantCharts(t.id).pipe(
                    catchError(() => of<TenantCharts>({
                      claimsByMonth: [], contributionsAmountByMonth: [], paymentsAmountByMonth: [],
                    })),
                  ),
        });
      }),
    ).subscribe({
      next: (result) => {
        if (!result) return;
        this.stats = { ...EMPTY_STATS, ...result.stats };
        this.applyCharts(result.charts);
        this.loading = false;
      },
      error: (err) => {
        this.loadError = err?.error?.detail || 'Could not load dashboard stats.';
        this.loading = false;
      },
    }));

    this.applyCharts({ claimsByMonth: [], contributionsAmountByMonth: [], paymentsAmountByMonth: [] });

    this.subs.push(this.permissions.permissions$.subscribe(set => {
      this.permissionsResolved = true;
      const allowed = this.tabs.filter(t => set.has(t.permission));
      this.hasAnyView = allowed.length > 0;

      // Auto-select on first resolution. If the user later loses the active
      // tab's permission (after a role swap), fall back to the first remaining
      // tab so we never sit on a blank panel they're not allowed to see.
      if (!this.activeTab || !set.has(this.tabFor(this.activeTab).permission)) {
        this.activeTab = allowed.length ? allowed[0].id : null;
      }
    }));
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  /** Imperatively select a tab — wired to the tab-strip click handlers. */
  selectTab(id: DashboardTab): void {
    this.activeTab = id;
  }

  private tabFor(id: DashboardTab): TabDef {
    return this.tabs.find(t => t.id === id)!;
  }

  /**
   * Wraps each flat trend array in the {@code [{ name, series }]} envelope
   * ngx-charts expects, padding to a full 12-month series so the chart
   * always has tick labels to render — even when the backend returns an
   * empty array (endpoint not yet deployed, or the tenant has zero data).
   */
  private applyCharts(charts: TenantCharts): void {
    const claimsPoints        = this.padToTwelveMonths(charts.claimsByMonth);
    const contributionsPoints = this.padToTwelveMonths(charts.contributionsAmountByMonth);
    const paymentsPoints      = this.padToTwelveMonths(charts.paymentsAmountByMonth);

    this.claimsSeries        = [{ name: 'Claims',        series: claimsPoints }];
    this.contributionsSeries = [{ name: 'Contributions', series: contributionsPoints }];
    this.paymentsSeries      = [{ name: 'Payments',      series: paymentsPoints }];

    this.claimsMax        = Math.max(5,    ...claimsPoints.map(p => p.value));
    this.contributionsMax = Math.max(1000, ...contributionsPoints.map(p => p.value));
    this.paymentsMax      = Math.max(1000, ...paymentsPoints.map(p => p.value));
  }

  /**
   * Build a 12-month series ending at the current month, merging values from
   * the supplied points (keyed by their {@code "YYYY-MM"} bucket). Months
   * with no data show 0. Output names are short month labels (Jan…Dec) with
   * the year suffix appended for the oldest/newest month so adjacent years
   * don't collide visually on the chart.
   */
  private padToTwelveMonths(points: TrendPoint[]): TrendPoint[] {
    // Index incoming points by their YYYY-MM bucket.
    const byBucket = new Map<string, number>();
    for (const p of points ?? []) {
      byBucket.set(p.name, Number(p.value) || 0);
    }
    const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    const now = new Date();
    const out: TrendPoint[] = [];
    for (let i = 11; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
      const yyyy = d.getFullYear();
      const mm = String(d.getMonth() + 1).padStart(2, '0');
      const bucket = `${yyyy}-${mm}`;
      out.push({ name: months[d.getMonth()], value: byBucket.get(bucket) ?? 0 });
    }
    return out;
  }
}
