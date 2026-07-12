import { BehaviorSubject, of } from 'rxjs';
import { ClaimsComponent } from './claims.component';
import { Claim, ClaimsService } from '../../core/services/claims.service';
import { PermissionService } from '../../core/security/permission.service';
import { TenantService } from '../../core/services/tenant.service';

/**
 * Component-level spec for ClaimsComponent. Drives the class directly (no
 * TestBed) — the interesting logic lives in the {@code rebuildTabs} and
 * {@code applyFilter} methods, not in the template. Everything the tests
 * touch is a plain property, so the constructor-only pattern used across
 * the tenant module (see BadDebtsListComponent.spec) fits cleanly.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>Type-tab gating: user permissions × tenant.insuranceLines.</li>
 *   <li>drug-only lockdown (users holding only claims:view_drug never
 *       see medical rows, tabs are hidden, filter is pinned).</li>
 *   <li>selectType() flips the filtered row set without a re-fetch.</li>
 * </ul>
 */
describe('ClaimsComponent', () => {
  let claimsService: jasmine.SpyObj<ClaimsService>;
  let tenant: TenantService & { tenant$: BehaviorSubject<any> };
  let permissions: PermissionService & { permissions$: BehaviorSubject<ReadonlySet<string>> };
  let router: jasmine.SpyObj<{ navigate: (...args: any[]) => any }>;
  let component: ClaimsComponent;

  // Fixture rows — kept minimal; only the fields the filter and columns
  // actually read are populated. Everything else is defaulted.
  const claim = (id: string, claimType: string): Claim => ({
    id,
    claimNumber: `C-${id}`,
    memberId: 'm1',
    providerId: 'p1',
    schemeId: 's1',
    claimType,
    status: 'SUBMITTED',
    serviceDate: '2026-07-01',
    claimedAmount: '100.00',
    currencyCode: 'USD',
    createdAt: '2026-07-01T00:00:00Z',
  });

  const rows: Claim[] = [
    claim('1', 'medical'),
    claim('2', 'drug'),
    claim('3', 'MEDICAL'),   // case-insensitive coverage
    claim('4', 'Drug'),
  ];

  function build(opts: {
    perms: ReadonlyArray<string>;
    insuranceLines: string[];
  }): void {
    permissions.permissions$.next(new Set(opts.perms));
    (permissions as any).has = (p: string) => opts.perms.includes(p);
    tenant.tenant$.next({ id: 't1', insuranceLines: opts.insuranceLines });
    (tenant as any).getTenant = () => ({ id: 't1', insuranceLines: opts.insuranceLines });
    component.ngOnInit();
  }

  beforeEach(() => {
    claimsService = jasmine.createSpyObj<ClaimsService>('ClaimsService', ['list']);
    claimsService.list.and.returnValue(of(rows));

    // TenantService is a BehaviorSubject-backed store — hand-roll a small
    // stand-in with the two members the component reads.
    const tenant$ = new BehaviorSubject<any>(null);
    tenant = { tenant$, getTenant: () => tenant$.getValue() } as unknown as
      TenantService & { tenant$: BehaviorSubject<any> };

    // Same pattern for PermissionService — reactive stream + sync has().
    const permissions$ = new BehaviorSubject<ReadonlySet<string>>(new Set());
    permissions = {
      permissions$,
      has: (p: string) => permissions$.getValue().has(p),
    } as unknown as PermissionService & { permissions$: BehaviorSubject<ReadonlySet<string>> };

    router = jasmine.createSpyObj('Router', ['navigate']);

    component = new ClaimsComponent(claimsService, tenant as any, permissions as any, router as any);
  });

  afterEach(() => component.ngOnDestroy());

  // ------------------------------------------------------------------
  // Type-tab gating: permission × insurance-line cross-product
  // ------------------------------------------------------------------

  describe('rebuildTabs — tab visibility', () => {
    it('claims:view + HEALTH tenant → [All, Medical, Drug]', () => {
      build({ perms: ['claims:view', 'claims:view_drug'], insuranceLines: ['HEALTH'] });
      expect(component.showTabs).toBeTrue();
      expect(component.typeTabs.map(t => t.value)).toEqual([null, 'medical', 'drug']);
      expect(component.activeType).toBeNull();
    });

    it('claims:view + non-HEALTH tenant → drug tab hidden', () => {
      // The rule is explicit: drug claims are a HEALTH-line concept, and
      // even a user who holds view_drug must not see the tab on a motor
      // or property-insurance tenant.
      build({ perms: ['claims:view', 'claims:view_drug'], insuranceLines: ['VEHICLE'] });
      expect(component.typeTabs.map(t => t.value)).toEqual([null, 'medical']);
    });

    it('claims:view only (no view_drug) + HEALTH tenant → drug tab hidden', () => {
      // Permission gating stacks with the insurance-line gate — either
      // missing hides Drug.
      build({ perms: ['claims:view'], insuranceLines: ['HEALTH'] });
      expect(component.typeTabs.map(t => t.value)).toEqual([null, 'medical']);
    });

    it('claims:view_drug only + HEALTH → drugOnly lockdown, tabs hidden, filter pinned', () => {
      // A pharmacy-adjudicator-only role: they hold view_drug but not
      // the umbrella view. The UI must never let them peek at medical
      // claims — {@code drugOnly} short-circuits both the tab strip and
      // the row filter.
      build({ perms: ['claims:view_drug'], insuranceLines: ['HEALTH'] });
      expect(component.showTabs).toBeFalse();
      expect(component.activeType).toBe('drug');
      expect(component.filtered.every(c => c.claimType.toLowerCase() === 'drug')).toBeTrue();
      expect(component.filtered.length).toBe(2);
    });

    it('claims:view_drug only + non-HEALTH → still drugOnly (permission trumps line)', () => {
      // Edge case: a non-HEALTH tenant with a drug-adjudicator user. The
      // insurance-line gate only hides the tab; it doesn't override an
      // explicit permission. drugOnly still applies so the user sees
      // only drug rows (which will typically be empty on such a tenant).
      build({ perms: ['claims:view_drug'], insuranceLines: ['VEHICLE'] });
      expect(component.showTabs).toBeFalse();
      expect(component.activeType).toBe('drug');
    });

    it('no relevant permission → empty tab set, filter unlocked to All (but rows unfiltered)', () => {
      // If the route guard let the user in (e.g. a role that grants
      // claims:manage_tariffs), we don't over-filter the fallback view.
      build({ perms: [], insuranceLines: ['HEALTH'] });
      expect(component.typeTabs).toEqual([]);
      expect(component.showTabs).toBeFalse();
      expect(component.activeType).toBeNull();
    });
  });

  // ------------------------------------------------------------------
  // selectType — pure client-side filter, no re-fetch
  // ------------------------------------------------------------------

  describe('selectType — filter application', () => {
    beforeEach(() => {
      build({ perms: ['claims:view', 'claims:view_drug'], insuranceLines: ['HEALTH'] });
      claimsService.list.calls.reset();
    });

    it('null shows all rows', () => {
      component.selectType(null);
      expect(component.filtered.length).toBe(rows.length);
    });

    it('"medical" excludes drug rows (case-insensitive)', () => {
      component.selectType('medical');
      expect(component.filtered.map(c => c.id).sort()).toEqual(['1', '3']);
    });

    it('"drug" excludes medical rows (case-insensitive)', () => {
      component.selectType('drug');
      expect(component.filtered.map(c => c.id).sort()).toEqual(['2', '4']);
    });

    it('no-op when already on the same tab', () => {
      // Guards a subtle regression where a re-emit of the same tab
      // could reset local state; the current impl short-circuits.
      component.selectType('drug');
      const first = component.filtered;
      component.selectType('drug');
      expect(component.filtered).toBe(first);
    });

    it('does NOT re-issue the list request on tab switch', () => {
      // Server has no filtered endpoint yet — all filtering is client-
      // side. If this fails, we've silently coupled the filter to a
      // network call and pagination will thrash.
      component.selectType('medical');
      component.selectType('drug');
      component.selectType(null);
      expect(claimsService.list).not.toHaveBeenCalled();
    });
  });

  // ------------------------------------------------------------------
  // Row action — page-level guard implies row visibility, so View is
  // always exposed; the detail route's own guard (claims:view OR
  // claims:view_drug) does the real gating.
  // ------------------------------------------------------------------

  describe('actions', () => {
    it('exposes a single View action, unconditional at row level', () => {
      build({ perms: ['claims:view_drug'], insuranceLines: ['HEALTH'] });
      expect(component.actions.length).toBe(1);
      expect(component.actions[0].label).toBe('View');
    });

    it('View action navigates to the claim detail route', () => {
      build({ perms: ['claims:view'], insuranceLines: ['HEALTH'] });
      component.actions[0].handler(rows[0]);
      expect(router.navigate).toHaveBeenCalledWith(['/tenant/claims', '1']);
    });
  });
});
