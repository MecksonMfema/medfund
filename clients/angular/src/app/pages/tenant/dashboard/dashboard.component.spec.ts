import { of } from 'rxjs';
import { TenantOperationalDashboardComponent } from './dashboard.component';
import {
  AdminService, TenantStats, TenantCharts,
  ClaimsStatusDistribution, RecentClaim, AdjudicatorWorkload, RecentContribution,
  PaymentsStatusDistribution, RecentPayment, TopPayee, PaymentMethodDistribution,
} from '../../../core/services/admin.service';
import { MockPermissionService } from '../../../_test-utils/mock-permission.service';
import { MockTenantService, buildTenant } from '../../../_test-utils/mock-tenant.service';
import { PermissionService } from '../../../core/security/permission.service';
import { TenantService } from '../../../core/services/tenant.service';

/**
 * The dashboard component pulls in nine ngx-charts wrappers and shared widgets
 * via its standalone {@code imports} array. Rendering all of those in a unit
 * test would either pull in the entire dependency graph or require an
 * ng-mocks-style stub registry, neither of which is appropriate for a class-
 * level logic test. We instantiate the component directly and exercise its
 * lifecycle methods so the template is never compiled — the class still
 * receives the same subscriptions, transformations, and state changes.
 */
function buildAdminService(): jasmine.SpyObj<AdminService> {
  const svc = jasmine.createSpyObj<AdminService>('AdminService', [
    'getTenantStats', 'getTenantCharts', 'getClaimsStatusDistribution',
    'getRecentClaims', 'getAdjudicatorWorkload', 'getRecentContributions',
    'getPaymentsStatusDistribution', 'getRecentPayments', 'getTopPayees',
    'getPaymentMethodDistribution',
  ]);

  const emptyStats = {} as TenantStats;
  const emptyCharts: TenantCharts = {
    claimsByMonth: [], contributionsAmountByMonth: [], paymentsAmountByMonth: [],
    claimsByMonthByCurrency: {}, contributionsAmountByMonthByCurrency: {}, paymentsAmountByMonthByCurrency: {},
  };

  svc.getTenantStats.and.returnValue(of(emptyStats));
  svc.getTenantCharts.and.returnValue(of(emptyCharts));
  svc.getClaimsStatusDistribution.and.returnValue(
    of<ClaimsStatusDistribution>({ total: 0, buckets: [] }));
  svc.getRecentClaims.and.returnValue(of<RecentClaim[]>([]));
  svc.getAdjudicatorWorkload.and.returnValue(of<AdjudicatorWorkload>({ unassigned: 0, adjudicators: [] }));
  svc.getRecentContributions.and.returnValue(of<RecentContribution[]>([]));
  svc.getPaymentsStatusDistribution.and.returnValue(
    of<PaymentsStatusDistribution>({ total: 0, buckets: [] }));
  svc.getRecentPayments.and.returnValue(of<RecentPayment[]>([]));
  svc.getTopPayees.and.returnValue(of<TopPayee[]>([]));
  svc.getPaymentMethodDistribution.and.returnValue(
    of<PaymentMethodDistribution>({ total: 0, buckets: [] }));

  return svc;
}

function instantiate(opts: {
  initialPerms?: ReadonlyArray<string>,
  superAdmin?: boolean,
  tenantId?: string,
} = {}): {
  comp: TenantOperationalDashboardComponent,
  perms: MockPermissionService,
  tenant: MockTenantService,
  admin: jasmine.SpyObj<AdminService>,
} {
  const perms = new MockPermissionService(opts.initialPerms ?? [], { superAdmin: opts.superAdmin });
  const tenant = new MockTenantService(buildTenant({ id: opts.tenantId ?? 'tenant-1' }));
  const admin = buildAdminService();
  const comp = new TenantOperationalDashboardComponent(
    perms as unknown as PermissionService,
    tenant as unknown as TenantService,
    admin,
  );
  return { comp, perms, tenant, admin };
}

describe('TenantOperationalDashboardComponent', () => {
  describe('permission-gated tab visibility', () => {
    it('hides every tab when the user has no view permissions', () => {
      const { comp } = instantiate({ initialPerms: [] });
      comp.ngOnInit();

      expect(comp.permissionsResolved).toBe(true);
      expect(comp.hasAnyView).toBe(false);
      expect(comp.activeTab).toBeNull();
      comp.ngOnDestroy();
    });

    it('auto-selects the first allowed tab in display order', () => {
      const { comp } = instantiate({ initialPerms: ['claims:view'] });
      comp.ngOnInit();

      expect(comp.hasAnyView).toBe(true);
      // Display order is billing, claims, finance; claims is the only allowed.
      expect(comp.activeTab).toBe('claims');
      comp.ngOnDestroy();
    });

    it('shows every tab for super admins (empty permission set bypassed)', () => {
      const { comp } = instantiate({ initialPerms: [], superAdmin: true });
      comp.ngOnInit();

      expect(comp.hasAnyView).toBe(true);
      expect(comp.activeTab).toBe('billing');
      comp.ngOnDestroy();
    });

    it('falls back to the first allowed tab when the active tab loses permission', () => {
      const { comp, perms } = instantiate({ initialPerms: ['claims:view', 'finance:view'] });
      comp.ngOnInit();
      comp.activeTab = 'finance';

      perms.emit(['claims:view']); // revoke finance

      expect(comp.activeTab).toBe('claims');
      comp.ngOnDestroy();
    });
  });

  describe('chart data shape regression', () => {
    it('falls back to blended series when no per-currency data is available', () => {
      const { comp, perms, admin } = instantiate({ initialPerms: ['billing:view'] });
      admin.getTenantCharts.and.returnValue(of<TenantCharts>({
        claimsByMonth: [{ name: 'Jan', value: 4 }, { name: 'Feb', value: 6 }],
        contributionsAmountByMonth: [{ name: 'Jan', value: 100 }, { name: 'Feb', value: 200 }],
        paymentsAmountByMonth: [{ name: 'Jan', value: 50 }, { name: 'Feb', value: 75 }],
        claimsByMonthByCurrency: {},
        contributionsAmountByMonthByCurrency: {},
        paymentsAmountByMonthByCurrency: {},
      }));
      comp.ngOnInit();
      perms.emit(['billing:view']);

      expect(comp.contributionsSeries.length).toBe(1);
      expect(comp.contributionsSeries[0].name).toBe('Contributions');
      expect(comp.financeChartsByCurrency.length).toBe(1);
      expect(comp.financeChartsByCurrency[0].code).toBe('All');
      expect(comp.financeChartsByCurrency[0].data.map(d => d.name))
        .toEqual(['Transactions', 'Payments']);
      comp.ngOnDestroy();
    });

    it('renders one series per currency when per-currency data is present', () => {
      const { comp, perms, admin } = instantiate({ initialPerms: ['billing:view'] });
      admin.getTenantCharts.and.returnValue(of<TenantCharts>({
        claimsByMonth: [], contributionsAmountByMonth: [], paymentsAmountByMonth: [],
        claimsByMonthByCurrency: {},
        contributionsAmountByMonthByCurrency: {
          USD: [{ name: 'Jan', value: 100 }],
          EUR: [{ name: 'Jan', value: 50 }],
        },
        paymentsAmountByMonthByCurrency: {
          USD: [{ name: 'Jan', value: 30 }],
        },
      }));
      comp.ngOnInit();
      // ngOnInit ends with applyCharts(EMPTY_CHARTS), which resets state. Push
      // a fresh permission emission to re-fire combineLatest and pick up the
      // overridden admin mock data — this is what HTTP latency would do in
      // production but synchronous test mocks bypass.
      perms.emit(['billing:view']);

      expect(comp.contributionsSeries.map(s => s.name).sort()).toEqual(['EUR', 'USD']);
      expect(comp.financeChartsByCurrency.map(c => c.code).sort()).toEqual(['EUR', 'USD']);
      comp.ngOnDestroy();
    });

    it('pads finance series to 12 months with zero-fill for missing labels', () => {
      const { comp, perms, admin } = instantiate({ initialPerms: ['finance:view'] });
      admin.getTenantCharts.and.returnValue(of<TenantCharts>({
        claimsByMonth: [], contributionsAmountByMonth: [], paymentsAmountByMonth: [],
        claimsByMonthByCurrency: {},
        contributionsAmountByMonthByCurrency: { USD: [{ name: 'Jan', value: 100 }] },
        paymentsAmountByMonthByCurrency:     { USD: [{ name: 'Feb', value: 50  }] },
      }));
      comp.ngOnInit();
      perms.emit(['finance:view']);

      const usd = comp.financeChartsByCurrency.find(c => c.code === 'USD');
      expect(usd).toBeDefined();
      expect(usd!.data[0].series.length).toBe(12);
      expect(usd!.data[1].series.length).toBe(12);
      comp.ngOnDestroy();
    });
  });

  describe('empty-state placeholder data', () => {
    it('renders a 5-slice pipeline placeholder when no claim data exists', () => {
      const { comp, tenant } = instantiate({ initialPerms: ['claims:view'] });
      comp.ngOnInit();
      tenant.setTenant(buildTenant({ id: 'tenant-1' }));

      expect(comp.pipelineHasData).toBe(false);
      expect(comp.statusPieData.length).toBe(5);
      expect(comp.statusPieData.every(s => s.value === 1)).toBe(true);
      comp.ngOnDestroy();
    });

    it('renders a placeholder payment-method donut when no method data exists', () => {
      const { comp, tenant } = instantiate({ initialPerms: ['finance:view'] });
      comp.ngOnInit();
      tenant.setTenant(buildTenant({ id: 'tenant-1' }));

      expect(comp.methodHasData).toBe(false);
      expect(comp.methodChartData.length).toBe(5);
      comp.ngOnDestroy();
    });
  });

  describe('client-side contribution filters', () => {
    it('filters by status chip toggle (idempotent on second click)', () => {
      const { comp } = instantiate();
      comp.recentContributions = [
        { id: '1', memberName: 'Alice', amount: 10, status: 'paid',    createdAt: '2026-01-01', paymentMethod: 'card', periodEnd: '2026-01-31' } as never,
        { id: '2', memberName: 'Bob',   amount: 20, status: 'pending', createdAt: '2026-01-02', paymentMethod: 'card', periodEnd: '2026-01-31' } as never,
      ];

      comp.setContribStatusFilter('paid');
      expect(comp.filteredContributions.map(c => c.id)).toEqual(['1']);

      comp.setContribStatusFilter('paid'); // toggle off
      expect(comp.filteredContributions.length).toBe(2);
    });

    it('filters by recipient when set', () => {
      const { comp } = instantiate();
      comp.recentContributions = [
        { id: '1', memberName: 'Alice', amount: 10, status: 'paid',    createdAt: '2026-01-01' } as never,
        { id: '2', memberName: 'Bob',   amount: 20, status: 'pending', createdAt: '2026-01-02' } as never,
      ];

      comp.setRecipient('Alice');
      expect(comp.filteredContributions.map(c => c.id)).toEqual(['1']);
      comp.setRecipient('__all__');
      expect(comp.filteredContributions.length).toBe(2);
    });

    it('extracts unique recipient names in sorted order', () => {
      const { comp } = instantiate();
      comp.recentContributions = [
        { id: '1', memberName: 'Charlie' } as never,
        { id: '2', memberName: 'Alice' } as never,
        { id: '3', memberName: 'Charlie' } as never,
      ];
      expect(comp.recipientOptions).toEqual(['Alice', 'Charlie']);
    });
  });

  describe('utility helpers', () => {
    it('statusLabel pretty-prints snake_case enums', () => {
      const { comp } = instantiate();
      expect(comp.statusLabel('in_adjudication')).toBe('In Adjudication');
      expect(comp.statusLabel('')).toBe('');
    });

    it('workloadPercent returns 0 when max is 0 (no divide-by-zero)', () => {
      const { comp } = instantiate();
      comp.workloadMax = 0;
      expect(comp.workloadPercent(10)).toBe(0);
    });

    it('payeePercent returns 0 when max is 0', () => {
      const { comp } = instantiate();
      comp.payeeMax = 0;
      expect(comp.payeePercent(123)).toBe(0);
    });

    it('primaryContribThisMonth defaults to USD 0 when no data', () => {
      const { comp } = instantiate();
      expect(comp.primaryContribThisMonth).toEqual({ currency: 'USD', amount: 0 });
    });
  });

  describe('chart-period toggle', () => {
    it('does not trigger refetch when the period is unchanged', () => {
      const { comp, admin, tenant } = instantiate({ initialPerms: ['billing:view'] });
      comp.ngOnInit();
      tenant.setTenant(buildTenant({ id: 'tenant-1' }));
      const initialCalls = admin.getTenantCharts.calls.count();

      comp.setChartPeriod('month'); // same as default
      expect(admin.getTenantCharts.calls.count()).toBe(initialCalls);
      comp.ngOnDestroy();
    });

    it('refetches when the period changes', () => {
      const { comp, admin, tenant } = instantiate({ initialPerms: ['billing:view'] });
      comp.ngOnInit();
      tenant.setTenant(buildTenant({ id: 'tenant-1' }));
      const initialCalls = admin.getTenantCharts.calls.count();

      comp.setChartPeriod('week');
      expect(admin.getTenantCharts.calls.count()).toBeGreaterThan(initialCalls);
      comp.ngOnDestroy();
    });
  });
});
