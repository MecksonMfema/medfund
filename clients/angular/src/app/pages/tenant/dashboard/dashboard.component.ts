import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { LegendPosition } from '@swimlane/ngx-charts';
import { forkJoin, of, Subscription } from 'rxjs';
import { catchError, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { HasPermissionDirective } from '../../../shared/directives/has-permission.directive';
import { GroupedBarChartComponent } from '../../../shared/components/charts/grouped-bar-chart/grouped-bar-chart.component';
import { LineChartComponent } from '../../../shared/components/charts/line-chart/line-chart.component';
import { MASCA_LEGACY_PALETTE, MASCA_TWIN_PALETTE } from '../../../shared/components/charts/chart-colors';
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

const EMPTY_CHARTS: TenantCharts = {
  claimsByMonth: [], contributionsAmountByMonth: [], paymentsAmountByMonth: [],
  claimsByMonthByCurrency: {}, contributionsAmountByMonthByCurrency: {}, paymentsAmountByMonthByCurrency: {},
};

type DashboardTab = 'claims' | 'billing' | 'finance';

interface TabDef {
  id: DashboardTab;
  label: string;
  permission: PermissionKey;
}

interface MultiSeries {
  name: string;
  series: TrendPoint[];
}

interface CurrencyChart {
  /** Currency code — used as chart title and key. */
  code: string;
  /** Two-series envelope: Transactions + Payments for this currency. */
  data: MultiSeries[];
  /** Y-axis hint so the chart still renders meaningfully when values are zero. */
  yMax: number;
}

/**
 * Operational dashboard — surfaces the tenant's at-a-glance stats grouped by
 * domain in a tabbed layout. Each tab (and its contents) is gated by the
 * matching `*:view` permission, so a finance-only user sees only the Finance
 * tab; a tenant admin sees all three. Charts mirror the legacy MASCA
 * dashboards exactly:
 *
 * - Claims  → grouped bar (one bar per active currency per month) — legacy
 *             Masca-Claims-Admin / ClaimsGraph.
 * - Billing → multi-series line (one line per active currency on one chart)
 *             — legacy MASCA-Frontend / graphs.js.
 * - Finance → one line chart per active currency, each plotting Transactions
 *             vs Payments — legacy Masca-Finance-Typescript / topSection.
 *
 * The number of chart series is driven by {@code tenant_currency_config};
 * default tenants (USD only) get single-series charts, multi-currency
 * tenants get one series per active code.
 */
@Component({
  selector: 'app-tenant-operational-dashboard',
  standalone: true,
  imports: [
    CommonModule, RouterLink, IconComponent, HasPermissionDirective,
    GroupedBarChartComponent, LineChartComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class TenantOperationalDashboardComponent implements OnInit, OnDestroy {
  tenantName = '';
  schemeLabelPlural = 'Schemes';
  loading = false;
  loadError: string | null = null;
  stats: TenantStats = { ...EMPTY_STATS };

  /** Multi-series shapes — one entry per active currency. */
  claimsSeries:        MultiSeries[] = [];
  contributionsSeries: MultiSeries[] = [];
  /** One twin-series chart per active currency (Finance tab). */
  financeChartsByCurrency: CurrencyChart[] = [];

  /** Y-axis hints so charts render meaningfully when values are zero or tiny. */
  claimsMax = 5;
  contributionsMax = 1000;

  /** Palette references for the templates. */
  readonly mascaPalette = MASCA_LEGACY_PALETTE;
  readonly mascaTwinPalette = MASCA_TWIN_PALETTE;
  /** Legend below the chart matches the legacy chart.js convention. */
  readonly legendBelow = LegendPosition.Below;

  /** True once the permission set has finished its first load — gates the empty state. */
  permissionsResolved = false;
  hasAnyView = false;

  /** Which domain tab is currently active. {@code null} until permissions resolve. */
  activeTab: DashboardTab | null = null;

  /**
   * The three tabs in display order. {@link permission} drives both the
   * `*hasPermission` gate on the tab button and the auto-select fallback.
   * The legacy MASCA dashboards have no Members tab — members surface as
   * stats elsewhere.
   */
  readonly tabs: ReadonlyArray<TabDef> = [
    { id: 'claims',  label: 'Claims',  permission: 'claims:view'  as PermissionKey },
    { id: 'billing', label: 'Billing', permission: 'billing:view' as PermissionKey },
    { id: 'finance', label: 'Finance', permission: 'finance:view' as PermissionKey },
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
                    catchError(() => of<TenantCharts>(EMPTY_CHARTS)),
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

    this.applyCharts(EMPTY_CHARTS);

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
   * Walks the per-currency response into ngx-charts envelopes:
   *
   * - claimsSeries / contributionsSeries: multi-series shape
   *   `[{ name: 'USD', series: [...] }, ...]` — one entry per active
   *   currency. Single-currency tenants get one entry, which the
   *   grouped-bar / multi-line components render as a single series.
   * - financeChartsByCurrency: one `{ code, data, yMax }` per currency,
   *   each `data` carrying two series (Transactions + Payments) for the
   *   twin-line chart shape.
   *
   * If the per-currency response is empty (older backend, or genuinely
   * no configured currencies), we fall back to the blended-currency
   * fields so the dashboard never renders blank.
   */
  private applyCharts(charts: TenantCharts): void {
    const claimsByCurrency        = charts.claimsByMonthByCurrency        ?? {};
    const contributionsByCurrency = charts.contributionsAmountByMonthByCurrency ?? {};
    const paymentsByCurrency      = charts.paymentsAmountByMonthByCurrency ?? {};

    const claimsCodes        = Object.keys(claimsByCurrency);
    const contributionsCodes = Object.keys(contributionsByCurrency);

    // Claims tab: one series per code, padded to 12 months.
    if (claimsCodes.length) {
      this.claimsSeries = claimsCodes.map(code => ({
        name: code,
        series: this.padToTwelveMonths(claimsByCurrency[code]),
      }));
    } else {
      // Fallback to the blended series for tenants/backends that haven't
      // populated the per-currency variant yet.
      this.claimsSeries = [{ name: 'Claims', series: this.padToTwelveMonths(charts.claimsByMonth) }];
    }

    // Billing tab: same shape, applied to contributions.
    if (contributionsCodes.length) {
      this.contributionsSeries = contributionsCodes.map(code => ({
        name: code,
        series: this.padToTwelveMonths(contributionsByCurrency[code]),
      }));
    } else {
      this.contributionsSeries = [{
        name: 'Contributions',
        series: this.padToTwelveMonths(charts.contributionsAmountByMonth),
      }];
    }

    // Finance tab: union of currencies seen in either contributions or
    // payments, so a tenant configured for USD + ZWL still gets two charts
    // even if one currency had no activity in the window.
    const financeCodes = Array.from(new Set([
      ...contributionsCodes,
      ...Object.keys(paymentsByCurrency),
    ]));
    if (financeCodes.length) {
      this.financeChartsByCurrency = financeCodes.map(code => {
        const tx       = this.padToTwelveMonths(contributionsByCurrency[code] ?? []);
        const payments = this.padToTwelveMonths(paymentsByCurrency[code] ?? []);
        const yMax = Math.max(1000, ...tx.map(p => p.value), ...payments.map(p => p.value));
        return {
          code,
          data: [
            { name: 'Transactions', series: tx },
            { name: 'Payments',     series: payments },
          ],
          yMax,
        };
      });
    } else {
      // Blended fallback — one chart, no currency split.
      const tx       = this.padToTwelveMonths(charts.contributionsAmountByMonth);
      const payments = this.padToTwelveMonths(charts.paymentsAmountByMonth);
      this.financeChartsByCurrency = [{
        code: 'All',
        data: [
          { name: 'Transactions', series: tx },
          { name: 'Payments',     series: payments },
        ],
        yMax: Math.max(1000, ...tx.map(p => p.value), ...payments.map(p => p.value)),
      }];
    }

    // Y-axis hints across all series, so the bar / line charts render
    // meaningfully when one series happens to be zero.
    this.claimsMax = Math.max(5,
      ...this.claimsSeries.flatMap(s => s.series.map(p => p.value)));
    this.contributionsMax = Math.max(1000,
      ...this.contributionsSeries.flatMap(s => s.series.map(p => p.value)));
  }

  /**
   * Build a 12-month series ending at the current month, merging values from
   * the supplied points (keyed by their {@code "YYYY-MM"} bucket). Months
   * with no data show 0. Output names are short month labels (Jan…Dec).
   */
  private padToTwelveMonths(points: TrendPoint[] | undefined): TrendPoint[] {
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
