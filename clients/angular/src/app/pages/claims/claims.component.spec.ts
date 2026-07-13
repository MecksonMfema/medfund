import { BehaviorSubject, of } from 'rxjs';
import { ClaimsComponent } from './claims.component';
import { ClaimRow, ClaimsService, PageResponse } from '../../core/services/claims.service';
import { PermissionService } from '../../core/security/permission.service';
import { TenantService } from '../../core/services/tenant.service';

/**
 * Component-level spec for ClaimsComponent — server-side paginated version.
 * Drives the class directly (no TestBed).
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>Type-tab gating: user permissions × tenant.insuranceLines.</li>
 *   <li>Drug-only lockdown: users holding only claims:view_drug get the
 *       server filter pinned to claim_type=drug on every request.</li>
 *   <li>selectType() refetches with the new claimType (server-side filter).</li>
 *   <li>onPageChange / onSearchChange / onSortChange all re-issue the paged
 *       request with the right params.</li>
 * </ul>
 */
describe('ClaimsComponent', () => {
  let claimsService: jasmine.SpyObj<ClaimsService>;
  let tenant: TenantService & { tenant$: BehaviorSubject<any> };
  let permissions: PermissionService & { permissions$: BehaviorSubject<ReadonlySet<string>> };
  let router: jasmine.SpyObj<{ navigate: (...args: any[]) => any }>;
  let component: ClaimsComponent;

  const emptyPage = (): PageResponse<ClaimRow> => ({
    content: [], total: 0, page: 0, size: 50, totalPages: 1,
  });

  const rowPage = (): PageResponse<ClaimRow> => ({
    content: [{
      id: 'c1', claimNumber: 'C-000001',
      memberId: 'm1', memberName: 'Alice Ndlovu', memberNumber: 'MBR-000001',
      providerId: 'p1', providerName: 'Harare Clinic',
      claimType: 'medical', status: 'VERIFIED',
      serviceDate: '2026-07-01', claimedAmount: '100.00',
      currencyCode: 'USD', createdAt: '2026-07-01T00:00:00Z',
    }],
    total: 1, page: 0, size: 50, totalPages: 1,
  });

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
    claimsService = jasmine.createSpyObj<ClaimsService>('ClaimsService', ['listPaged']);
    claimsService.listPaged.and.returnValue(of(emptyPage()));

    const tenant$ = new BehaviorSubject<any>(null);
    tenant = { tenant$, getTenant: () => tenant$.getValue() } as unknown as
      TenantService & { tenant$: BehaviorSubject<any> };

    const permissions$ = new BehaviorSubject<ReadonlySet<string>>(new Set());
    permissions = {
      permissions$,
      has: (p: string) => permissions$.getValue().has(p),
    } as unknown as PermissionService & { permissions$: BehaviorSubject<ReadonlySet<string>> };

    router = jasmine.createSpyObj('Router', ['navigate']);

    component = new ClaimsComponent(claimsService, tenant as any, permissions as any, router as any);
  });

  afterEach(() => component.ngOnDestroy());

  // ── Type-tab gating ─────────────────────────────────────────────────

  describe('rebuildTabs — tab visibility', () => {
    it('claims:view + HEALTH tenant → [All, Medical, Drug]', () => {
      build({ perms: ['claims:view', 'claims:view_drug'], insuranceLines: ['HEALTH'] });
      expect(component.showTabs).toBeTrue();
      expect(component.typeTabs.map(t => t.value)).toEqual([null, 'medical', 'drug']);
      expect(component.activeType).toBeNull();
    });

    it('non-HEALTH tenant hides the drug tab', () => {
      build({ perms: ['claims:view', 'claims:view_drug'], insuranceLines: ['VEHICLE'] });
      expect(component.typeTabs.map(t => t.value)).toEqual([null, 'medical']);
    });

    it('claims:view_drug only + HEALTH → drug-only lockdown', () => {
      build({ perms: ['claims:view_drug'], insuranceLines: ['HEALTH'] });
      expect(component.showTabs).toBeFalse();
      expect(component.activeType).toBe('drug');
    });
  });

  // ── Server-side filter/pagination contract ──────────────────────────

  describe('server-side pagination', () => {
    beforeEach(() => {
      build({ perms: ['claims:view', 'claims:view_drug'], insuranceLines: ['HEALTH'] });
      claimsService.listPaged.calls.reset();
    });

    it('ngOnInit fires a paginated request with default sort', () => {
      // Reload the component to re-observe the ngOnInit fetch.
      component = new ClaimsComponent(claimsService, tenant as any, permissions as any, router as any);
      claimsService.listPaged.calls.reset();
      component.ngOnInit();
      expect(claimsService.listPaged).toHaveBeenCalledTimes(1);
      expect(claimsService.listPaged.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({
        page: 0,
        size: 50,
        sortKey: 'submissionDate',
        sortDirection: 'desc',
      }));
    });

    it('selectType("medical") re-issues with claimType=medical', () => {
      component.selectType('medical');
      expect(claimsService.listPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
        claimType: 'medical',
        page: 0,
      }));
    });

    it('selectType(null) omits claimType — All-tab returns everything', () => {
      // Move off the All tab first so the switch back to null isn't a no-op.
      component.selectType('medical');
      claimsService.listPaged.calls.reset();

      component.selectType(null);

      expect(claimsService.listPaged.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({
        claimType: undefined,
      }));
    });

    it('no-op when already on the same tab', () => {
      component.selectType('drug');
      claimsService.listPaged.calls.reset();
      component.selectType('drug');
      expect(claimsService.listPaged).not.toHaveBeenCalled();
    });

    it('onPageChange forwards the zero-indexed page number', () => {
      component.onPageChange(4);
      expect(component.page).toBe(4);
      expect(claimsService.listPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
        page: 3,
      }));
    });

    it('onSearchChange resets to page 1 and forwards q', () => {
      component.page = 5;
      component.onSearchChange('alice');
      expect(component.page).toBe(1);
      expect(claimsService.listPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
        q: 'alice',
        page: 0,
      }));
    });

    it('onSortChange forwards key + direction, resets to page 1', () => {
      component.page = 3;
      component.onSortChange({ key: 'claimedAmount', direction: 'asc' });
      expect(component.sortKey).toBe('claimedAmount');
      expect(component.sortDirection).toBe('asc');
      expect(component.page).toBe(1);
      expect(claimsService.listPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
        sortKey: 'claimedAmount',
        sortDirection: 'asc',
        page: 0,
      }));
    });

    it('hydrates totalCount + totalPages from the envelope', () => {
      claimsService.listPaged.and.returnValue(of(rowPage()));
      component.onPageChange(1);
      expect(component.rows.length).toBe(1);
      expect(component.rows[0].memberName).toBe('Alice Ndlovu');
      expect(component.totalCount).toBe(1);
    });
  });

  // ── Row action ──────────────────────────────────────────────────────

  describe('actions', () => {
    it('exposes a single View action, unconditional at row level', () => {
      build({ perms: ['claims:view_drug'], insuranceLines: ['HEALTH'] });
      expect(component.actions.length).toBe(1);
      expect(component.actions[0].label).toBe('View');
    });

    it('View action navigates to the claim detail route', () => {
      build({ perms: ['claims:view'], insuranceLines: ['HEALTH'] });
      const row = rowPage().content[0];
      component.actions[0].handler(row);
      expect(router.navigate).toHaveBeenCalledWith(['/tenant/claims', 'c1']);
    });
  });
});
