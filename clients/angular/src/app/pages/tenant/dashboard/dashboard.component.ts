import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { LegendPosition } from '@swimlane/ngx-charts';
import { BehaviorSubject, combineLatest, forkJoin, of, Subscription } from 'rxjs';
import { catchError, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { IconComponent } from '../../../shared/components/icon/icon.component';
import { HasPermissionDirective } from '../../../shared/directives/has-permission.directive';
import { AreaChartComponent } from '../../../shared/components/charts/area-chart/area-chart.component';
import { PieChartComponent } from '../../../shared/components/charts/pie-chart/pie-chart.component';
import { DataTableComponent } from '../../../shared/components/data-table/data-table.component';
import { StatCardComponent } from '../../../shared/components/stat-card/stat-card.component';
import { MASCA_LEGACY_PALETTE, MASCA_TWIN_PALETTE } from '../../../shared/components/charts/chart-colors';
import { PermissionKey } from '../../../core/security/permissions';
import { PermissionService } from '../../../core/security/permission.service';
import { TenantService } from '../../../core/services/tenant.service';
import {
  AdminService, TenantStats, TenantCharts, TrendPoint,
  ClaimsStatusDistribution, RecentClaim, AdjudicatorWorkload,
  RecentContribution,
  PaymentsStatusDistribution, RecentPayment, TopPayee, PaymentMethodDistribution,
} from '../../../core/services/admin.service';

type ChartPeriod = 'day' | 'week' | 'month';

const EMPTY_DISTRIBUTION: ClaimsStatusDistribution = { total: 0, buckets: [] };
const EMPTY_WORKLOAD: AdjudicatorWorkload = { unassigned: 0, adjudicators: [] };

const EMPTY_STATS: TenantStats = {
  totalStaff: 0, activeStaff: 0, suspendedStaff: 0, pendingStaff: 0,
  totalMembers: 0, activeMembers: 0, enrolledMembers: 0,
  newMembersThisMonth: 0, newGroupsThisMonth: 0,
  claimsNewTasks: 0, claimsThisMonth: 0,
  claimsAcceptedThisMonth: 0, claimsRejectedThisMonth: 0,
  schemesActive: 0, contributionsPending: 0,
  contributionsAmountThisMonth: 0, contributionsAmountThisYear: 0,
  paymentsPending: 0, paymentsAmountThisMonth: 0, paymentsAmountThisYear: 0,
  paymentsAdvanceCount: 0, paymentsAdvanceAmount: 0, paymentsFailedCount: 0,
  claimsShortfallAmount: 0,
  contributionsAmountThisMonthByCurrency: {},
  contributionsAmountThisYearByCurrency:  {},
  paymentsAmountThisMonthByCurrency:      {},
  paymentsAmountThisYearByCurrency:       {},
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
    AreaChartComponent, PieChartComponent,
    DataTableComponent, StatCardComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class TenantOperationalDashboardComponent implements OnInit, OnDestroy {
  tenantName = '';
  schemeLabelPlural = 'Schemes';
  /** Tenant's default currency code (e.g. USD, ZAR, ZWL, EUR) — drives every
   *  amount-formatting pipe on this dashboard. Falls back to USD until the
   *  tenant snapshot loads. */
  tenantCurrency = 'USD';
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

  /** Granularity toggle on the claims volume chart. */
  readonly chartPeriods: ChartPeriod[] = ['day', 'week', 'month'];
  chartPeriod: ChartPeriod = 'month';
  private chartPeriod$ = new BehaviorSubject<ChartPeriod>('month');

  /** Pipeline pie chart — fed by /tenant-stats/claims-status-distribution. */
  statusDistribution: ClaimsStatusDistribution = EMPTY_DISTRIBUTION;
  statusPieData: { name: string; value: number }[] = [];

  /** Color per pipeline status — kept in sync with /platform/dashboard so the
   *  two surfaces look like one product. */
  readonly pipelineColors: Record<string, string> = {
    'Adjudicated':     '#2EC4B6',
    'Pending':         '#FF9F1C',
    'Rejected':        '#E71D36',
    'In Adjudication': '#00B4D8',
    'Submitted':       '#90E0EF',
  };

  /** RBAC-gated cards (see *hasPermission gates in the template). */
  recentClaims: RecentClaim[] = [];
  workload: AdjudicatorWorkload = EMPTY_WORKLOAD;
  workloadMax = 0;

  /** Column config for the recent-claims data-table. */
  readonly recentClaimColumns = [
    { key: 'claimNumber',    label: 'Claim #' },
    { key: 'status',         label: 'Status',        type: 'status' as const },
    { key: 'claimedAmount',  label: 'Amount',        type: 'currency' as const },
    { key: 'currencyCode',   label: 'Ccy' },
    { key: 'serviceDate',    label: 'Service Date',  type: 'date' as const },
    { key: 'createdAt',      label: 'Submitted',     type: 'date' as const },
  ];

  // ── Billing tab state — matches the reference billing dashboard layout. ──
  recentContributions: RecentContribution[] = [];

  readonly recentContributionColumns = [
    { key: 'id',             label: 'Contribution #' },
    { key: 'createdAt',      label: 'Issued Date',   type: 'date' as const },
    { key: 'memberName',     label: 'Member' },
    { key: 'amount',         label: 'Amount',        type: 'currency' as const },
    { key: 'paymentMethod',  label: 'Method' },
    { key: 'periodEnd',      label: 'Due Date',      type: 'date' as const },
    { key: 'status',         label: 'Status',        type: 'status' as const },
  ];

  /** Quick-filter chip state for the contributions table (client-side filter). */
  contribStatusFilter: string | null = null;
  readonly contribStatusFilters = ['paid', 'pending', 'overdue'];

  /** Date-range select state. Values are filter keys; labels live in the template. */
  dateRangeFilter: 'all' | 'thisMonth' | 'last30' | 'last90' | 'thisYear' = 'all';

  /** Recipient (member name) select state — null means "all". */
  recipientFilter: string | null = null;

  /** Unique recipient names extracted from the loaded contributions, sorted. */
  get recipientOptions(): string[] {
    const names = new Set<string>();
    for (const c of this.recentContributions) {
      if (c.memberName) names.add(c.memberName);
    }
    return Array.from(names).sort();
  }

  /** Client-side filter pipeline applied to the loaded contributions: status
   *  chip + status select + date range + recipient. Order doesn't matter
   *  since each is an independent predicate. */
  get filteredContributions(): RecentContribution[] {
    const now = Date.now();
    const dayMs = 86_400_000;
    let from = 0;
    if (this.dateRangeFilter === 'thisMonth') {
      const d = new Date();
      from = new Date(d.getFullYear(), d.getMonth(), 1).getTime();
    } else if (this.dateRangeFilter === 'last30') {
      from = now - 30 * dayMs;
    } else if (this.dateRangeFilter === 'last90') {
      from = now - 90 * dayMs;
    } else if (this.dateRangeFilter === 'thisYear') {
      const d = new Date();
      from = new Date(d.getFullYear(), 0, 1).getTime();
    }
    return this.recentContributions.filter(c => {
      if (this.contribStatusFilter && c.status !== this.contribStatusFilter) return false;
      if (this.recipientFilter      && c.memberName !== this.recipientFilter) return false;
      if (from > 0) {
        const created = new Date(c.createdAt).getTime();
        if (Number.isFinite(created) && created < from) return false;
      }
      return true;
    });
  }

  setContribStatusFilter(s: string | null): void {
    this.contribStatusFilter = this.contribStatusFilter === s ? null : s;
  }

  setDateRange(value: string): void {
    if (['all', 'thisMonth', 'last30', 'last90', 'thisYear'].includes(value)) {
      this.dateRangeFilter = value as typeof this.dateRangeFilter;
    }
  }

  setRecipient(value: string): void {
    this.recipientFilter = value === '__all__' ? null : value;
  }

  setStatusFromSelect(value: string): void {
    this.contribStatusFilter = value === '__all__' ? null : value;
  }

  /** Flatten a Record<currency, amount> into a sorted array, dropping zeros.
   *  Multi-currency tenants get multiple entries; single-currency tenants
   *  get one; empty tenants get []. */
  private byCurrencyEntries(map: Record<string, number> | undefined): { currency: string; amount: number }[] {
    if (!map) return [];
    return Object.entries(map)
      .map(([currency, amount]) => ({ currency, amount: Number(amount) || 0 }))
      .filter(e => e.amount > 0)
      .sort((a, b) => b.amount - a.amount);
  }

  /** Per-currency contributions this month, sorted by amount desc. */
  get contribAmountsThisMonth(): { currency: string; amount: number }[] {
    return this.byCurrencyEntries(this.stats.contributionsAmountThisMonthByCurrency);
  }

  /** Per-currency contributions this year, sorted by amount desc. */
  get contribAmountsThisYear(): { currency: string; amount: number }[] {
    return this.byCurrencyEntries(this.stats.contributionsAmountThisYearByCurrency);
  }

  /** Per-currency payments out this month, sorted by amount desc. */
  get paymentAmountsThisMonth(): { currency: string; amount: number }[] {
    return this.byCurrencyEntries(this.stats.paymentsAmountThisMonthByCurrency);
  }

  /** Per-currency payments out this year, sorted by amount desc. */
  get paymentAmountsThisYear(): { currency: string; amount: number }[] {
    return this.byCurrencyEntries(this.stats.paymentsAmountThisYearByCurrency);
  }

  get primaryPaymentsThisMonth(): { currency: string; amount: number } {
    return this.paymentAmountsThisMonth[0] ?? { currency: 'USD', amount: 0 };
  }
  get primaryPaymentsThisYear(): { currency: string; amount: number } {
    return this.paymentAmountsThisYear[0] ?? { currency: 'USD', amount: 0 };
  }

  // ── Finance tab — pipeline / table / payees / methods. ──────────────────
  paymentsDistribution: PaymentsStatusDistribution = { total: 0, buckets: [] };
  paymentsPieData: { name: string; value: number }[] = [];
  recentPayments: RecentPayment[] = [];
  topPayees: TopPayee[] = [];
  payeeMax = 1;
  methodDistribution: PaymentMethodDistribution = { total: 0, buckets: [] };
  methodChartData: { name: string; value: number }[] = [];

  /** Twin-series cash-flow chart data: Contributions in vs Payments out. */
  cashFlowSeries: { name: string; series: TrendPoint[] }[] = [];
  cashFlowMax = 1000;

  readonly paymentsColors: Record<string, string> = {
    'Completed':  '#2EC4B6',
    'Pending':    '#FF9F1C',
    'Processing': '#00B4D8',
    'Failed':     '#E71D36',
    'Cancelled':  '#9CA3AF',
    'Refunded':   '#90E0EF',
  };

  /** Color per payment method — drives the methods donut + legend. */
  readonly methodColors: Record<string, string> = {
    'Bank Transfer': '#2EC4B6',
    'Eft':           '#00B4D8',
    'Mobile Money':  '#FF9F1C',
    'Card':          '#90E0EF',
    'Cash':          '#9CA3AF',
    'Cheque':        '#03045E',
    'Unknown':       '#9CA3AF',
  };

  readonly recentPaymentColumns = [
    { key: 'paymentNumber',  label: 'Payment #' },
    { key: 'createdAt',      label: 'Date',         type: 'date' as const },
    { key: 'providerName',   label: 'Payee' },
    { key: 'amount',         label: 'Amount',       type: 'currency' as const },
    { key: 'paymentType',    label: 'Type' },
    { key: 'paymentMethod',  label: 'Method' },
    { key: 'status',         label: 'Status',       type: 'status' as const },
  ];

  get paymentsHasData(): boolean { return this.paymentsDistribution.total > 0; }
  get methodHasData(): boolean   { return this.methodDistribution.total > 0; }
  payeePercent(received: string | number): number {
    if (this.payeeMax <= 0) return 0;
    return Math.round((Number(received) || 0) / this.payeeMax * 100);
  }

  /** First entry (or a USD 0 default) — safe to bind even with empty data.
   *  Default to USD per the platform's "assume USD when no currency configured"
   *  contract; never renders as undefined or null. */
  get primaryContribThisMonth(): { currency: string; amount: number } {
    return this.contribAmountsThisMonth[0] ?? { currency: 'USD', amount: 0 };
  }
  get primaryContribThisYear(): { currency: string; amount: number } {
    return this.contribAmountsThisYear[0] ?? { currency: 'USD', amount: 0 };
  }

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
    { id: 'billing', label: 'Billing', permission: 'billing:view' as PermissionKey },
    { id: 'claims',  label: 'Claims',  permission: 'claims:view'  as PermissionKey },
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
      this.tenantName        = t?.name                 ?? '';
      this.schemeLabelPlural = t?.schemeLabelPlural    ?? 'Schemes';
      this.tenantCurrency    = t?.defaultCurrencyCode  ?? 'USD';
    }));

    // Refetch every dashboard data source whenever the active tenant, the
    // chart-period toggle, or the user's permission set changes. We gate
    // recent-claims and adjudicator-workload by permission client-side so we
    // don't surface a "forbidden" error for users without access — the cards
    // are also hidden via *hasPermission in the template.
    this.subs.push(combineLatest([
      this.tenantService.tenant$.pipe(distinctUntilChanged((a, b) => a?.id === b?.id)),
      this.chartPeriod$,
      this.permissions.permissions$,
    ]).pipe(
      switchMap(([t, period, perms]) => {
        if (!t?.id) return of(null);
        this.loading = true;
        this.loadError = null;
        const canViewClaims    = perms.has('claims:view');
        const canViewWorkload  = perms.has('claims:assign');
        const canViewBilling   = perms.has('billing:view');
        const canViewFinance   = perms.has('finance:view');
        return forkJoin({
          stats:  this.adminService.getTenantStats(t.id),
          charts: this.adminService.getTenantCharts(t.id, period).pipe(
                    catchError(() => of<TenantCharts>(EMPTY_CHARTS)),
                  ),
          distribution: this.adminService.getClaimsStatusDistribution(t.id).pipe(
                    catchError(() => of<ClaimsStatusDistribution>(EMPTY_DISTRIBUTION)),
                  ),
          recent: canViewClaims
                    ? this.adminService.getRecentClaims(t.id).pipe(catchError(() => of<RecentClaim[]>([])))
                    : of<RecentClaim[]>([]),
          workload: canViewWorkload
                    ? this.adminService.getAdjudicatorWorkload(t.id).pipe(
                        catchError(() => of<AdjudicatorWorkload>(EMPTY_WORKLOAD)))
                    : of<AdjudicatorWorkload>(EMPTY_WORKLOAD),
          recentContributions: canViewBilling
                    ? this.adminService.getRecentContributions(t.id).pipe(catchError(() => of<RecentContribution[]>([])))
                    : of<RecentContribution[]>([]),
          paymentsDistribution: canViewFinance
                    ? this.adminService.getPaymentsStatusDistribution(t.id).pipe(
                        catchError(() => of<PaymentsStatusDistribution>({ total: 0, buckets: [] })))
                    : of<PaymentsStatusDistribution>({ total: 0, buckets: [] }),
          recentPayments: canViewFinance
                    ? this.adminService.getRecentPayments(t.id).pipe(catchError(() => of<RecentPayment[]>([])))
                    : of<RecentPayment[]>([]),
          topPayees: canViewFinance
                    ? this.adminService.getTopPayees(t.id).pipe(catchError(() => of<TopPayee[]>([])))
                    : of<TopPayee[]>([]),
          methodDistribution: canViewFinance
                    ? this.adminService.getPaymentMethodDistribution(t.id).pipe(
                        catchError(() => of<PaymentMethodDistribution>({ total: 0, buckets: [] })))
                    : of<PaymentMethodDistribution>({ total: 0, buckets: [] }),
        });
      }),
    ).subscribe({
      next: (result) => {
        if (!result) return;
        this.stats = { ...EMPTY_STATS, ...result.stats };
        this.applyCharts(result.charts);
        this.statusDistribution = result.distribution;
        const mapped = result.distribution.buckets.map(b => ({
          name:  this.statusLabel(b.status),
          value: b.count,
        }));
        // Empty-state mirror of /platform/dashboard: render a 5-slice placeholder
        // donut behind the "No claims yet" overlay so the chart isn't blank.
        this.statusPieData = result.distribution.total > 0 ? mapped : [
          { name: 'Adjudicated',     value: 1 },
          { name: 'Pending',         value: 1 },
          { name: 'In Adjudication', value: 1 },
          { name: 'Rejected',        value: 1 },
          { name: 'Submitted',       value: 1 },
        ];
        this.recentClaims = result.recent;
        this.workload = result.workload;
        this.workloadMax = Math.max(1, ...result.workload.adjudicators.map(a => a.count));

        this.recentContributions = result.recentContributions;

        // ── Finance tab — pipeline pie + method distribution mirror the
        // empty-state pattern: render placeholder slices behind the overlay
        // when no data exists yet. ────────────────────────────────────────
        this.paymentsDistribution = result.paymentsDistribution;
        const paymentsMapped = result.paymentsDistribution.buckets.map(b => ({
          name:  this.statusLabel(b.status),
          value: b.count,
        }));
        this.paymentsPieData = result.paymentsDistribution.total > 0 ? paymentsMapped : [
          { name: 'Completed',  value: 1 },
          { name: 'Pending',    value: 1 },
          { name: 'Processing', value: 1 },
          { name: 'Failed',     value: 1 },
          { name: 'Cancelled',  value: 1 },
        ];
        this.recentPayments = result.recentPayments;
        this.topPayees = result.topPayees;
        this.payeeMax = Math.max(1, ...result.topPayees.map(p => Number(p.received) || 0));
        this.methodDistribution = result.methodDistribution;
        const methodMapped = result.methodDistribution.buckets.map(b => ({
          name:  this.statusLabel(b.method),
          value: Number(b.amount) || 0,
        }));
        // Empty-state placeholder — same treatment as the pipeline pies.
        this.methodChartData = result.methodDistribution.total > 0 ? methodMapped : [
          { name: 'Bank Transfer', value: 1 },
          { name: 'Eft',           value: 1 },
          { name: 'Mobile Money',  value: 1 },
          { name: 'Card',          value: 1 },
          { name: 'Cash',          value: 1 },
        ];
        this.loading = false;
      },
      error: (err) => {
        this.loadError = err?.error?.detail || 'Could not load dashboard stats.';
        this.loading = false;
      },
    }));

    this.applyCharts(EMPTY_CHARTS);

    this.subs.push(this.permissions.permissions$.subscribe(() => {
      this.permissionsResolved = true;
      // Use PermissionService.has() so super admins (with an empty tenant-
      // scoped set by design) see every tab they need access to.
      const allowed = this.tabs.filter(t => this.permissions.has(t.permission));
      this.hasAnyView = allowed.length > 0;

      // Auto-select on first resolution. If the user later loses the active
      // tab's permission (after a role swap), fall back to the first remaining
      // tab so we never sit on a blank panel they're not allowed to see.
      if (!this.activeTab || !this.permissions.has(this.tabFor(this.activeTab).permission)) {
        this.activeTab = allowed.length ? allowed[0].id : null;
      }
    }));
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  /** Switch the claims-volume chart granularity. Triggers a refetch via combineLatest. */
  setChartPeriod(p: ChartPeriod): void {
    if (p === this.chartPeriod) return;
    this.chartPeriod = p;
    this.chartPeriod$.next(p);
  }

  /** Pretty-print a raw status enum for the pie chart legend. */
  statusLabel(raw: string): string {
    if (!raw) return '';
    return raw.split('_').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
  }

  /** Percentage of total claims represented by a workload row (0..100). */
  workloadPercent(count: number): number {
    if (this.workloadMax <= 0) return 0;
    return Math.round((count / this.workloadMax) * 100);
  }

  /** True iff the pie chart has at least one non-zero slice. Drives the
   *  empty-state overlay (mirrors /platform/dashboard's claimsHasData). */
  get pipelineHasData(): boolean {
    return this.statusDistribution.total > 0;
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

    // Claims tab: single-series area chart, mirroring the Tenant Growth
    // pattern in /platform/dashboard. Uses the blended-currency monthly
    // counts so a single line tracks total claim volume.
    // Claims trend ships from /tenant-stats/charts?period=day|week|month with
    // 12 server-paginated buckets in order; the label format ("Jul", "Wk 28",
    // "15 Jul") follows the period query param. Pass through as-is so the
    // x-axis labels follow the period filter — do NOT pad to calendar months.
    const claimsBlended = charts.claimsByMonth ?? [];
    this.claimsSeries = [{ name: 'Claims', series: claimsBlended }];
    const claimsMaxVal = claimsBlended.reduce((m, p) => Math.max(m, Number(p.value) || 0), 0);
    this.claimsMax = claimsMaxVal > 0 ? Math.ceil(claimsMaxVal * 1.2) : 5;

    // Billing tab — multi-currency aware. When the tenant has multiple active
    // currency configs, render one series per currency so each is plotted
    // independently. Single-currency tenants see a single series; tenants
    // with no per-currency data fall back to the blended sum (legacy).
    const contribByCcy = charts.contributionsAmountByMonthByCurrency ?? {};
    const contribCodes = Object.keys(contribByCcy);
    if (contribCodes.length >= 1) {
      this.contributionsSeries = contribCodes.map(code => ({
        name: code,
        series: contribByCcy[code] ?? [],
      }));
    } else {
      this.contributionsSeries = [{
        name: 'Contributions',
        series: charts.contributionsAmountByMonth ?? [],
      }];
    }
    const allContribValues = this.contributionsSeries.flatMap(s => s.series.map(p => Number(p.value) || 0));
    const contribMaxVal = allContribValues.length ? Math.max(...allContribValues) : 0;
    this.contributionsMax = contribMaxVal > 0 ? Math.ceil(contribMaxVal * 1.2) : 1000;

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

    // ── Finance tab — single twin-line cash-flow chart. Two series on one
    // canvas: Contributions in (revenue) and Payments out (disbursements).
    // Both backend series are period-aware, so the chart-period toggle
    // updates both legs together for an apples-to-apples comparison.
    const contribIn = charts.contributionsAmountByMonth ?? [];
    const paymentsOut = charts.paymentsAmountByMonth ?? [];
    this.cashFlowSeries = [
      { name: 'Contributions in', series: contribIn },
      { name: 'Payments out',     series: paymentsOut },
    ];
    const allCashFlow = [...contribIn, ...paymentsOut].map(p => Number(p.value) || 0);
    const cashFlowMaxVal = allCashFlow.length ? Math.max(...allCashFlow) : 0;
    this.cashFlowMax = cashFlowMaxVal > 0 ? Math.ceil(cashFlowMaxVal * 1.2) : 1000;

    // Y-axis hints across all series, so the bar / line charts render
    // meaningfully when one series happens to be zero.
    this.claimsMax = Math.max(5,
      ...this.claimsSeries.flatMap(s => s.series.map(p => p.value)));
    this.contributionsMax = Math.max(1000,
      ...this.contributionsSeries.flatMap(s => s.series.map(p => p.value)));
  }

  /**
   * Build a 12-month series ending at the current month, merging values from
   * the supplied points (keyed by their short month name — backend now ships
   * "Jan"…"Dec" labels directly). Months with no data show 0.
   */
  private padToTwelveMonths(points: TrendPoint[] | undefined): TrendPoint[] {
    const byMonth = new Map<string, number>();
    for (const p of points ?? []) {
      byMonth.set(p.name, Number(p.value) || 0);
    }
    const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    const now = new Date();
    const out: TrendPoint[] = [];
    for (let i = 11; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
      const label = months[d.getMonth()];
      out.push({ name: label, value: byMonth.get(label) ?? 0 });
    }
    return out;
  }
}
