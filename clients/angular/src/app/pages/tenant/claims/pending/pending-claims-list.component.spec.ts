import { of } from 'rxjs';
import { ActivatedRoute } from '@angular/router';
import { PendingClaimsListComponent } from './pending-claims-list.component';
import { ClaimRow, ClaimsService, PageResponse } from '../../../../core/services/claims.service';

class StubActivatedRoute {
  constructor(public snapshot: { data: Record<string, unknown> }) {}
}

/**
 * Guards the two contracts the pending-family list depends on:
 * <ol>
 *   <li>The five status routes (pending/accepted/rejected/staged/captured)
 *       share one component that seeds its filter from route data — the
 *       status pins to `presetStatus`, everything else fetches from the
 *       server via the new paginated endpoint.</li>
 *   <li>Search, sort, and paging all round-trip to the server. Regression
 *       here would silently reintroduce the client-side filter and defeat
 *       the whole point of this migration.</li>
 * </ol>
 */
describe('PendingClaimsListComponent', () => {
  let claims: jasmine.SpyObj<ClaimsService>;
  let router: jasmine.SpyObj<{ navigate: (...args: any[]) => any }>;

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

  function makeComponent(routeData: Record<string, unknown> = {}): PendingClaimsListComponent {
    const route = new StubActivatedRoute({ data: routeData }) as unknown as ActivatedRoute;
    const c = new PendingClaimsListComponent(claims, route, router as any);
    c.ngOnInit();
    return c;
  }

  beforeEach(() => {
    claims = jasmine.createSpyObj<ClaimsService>('ClaimsService', ['listPaged']);
    claims.listPaged.and.returnValue(of(emptyPage()));
    router = jasmine.createSpyObj('Router', ['navigate']);
  });

  it('presetStatus from route data pins the status filter', () => {
    const component = makeComponent({ presetStatus: 'ADJUDICATED', title: 'Accepted' });
    expect(component.statusFilter).toBe('ADJUDICATED');
    expect(component.presetStatus).toBe('ADJUDICATED');
    expect(component.pageTitle).toBe('Accepted');
    expect(claims.listPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      status: 'ADJUDICATED',
      page: 0,
      size: 50,
    }));
  });

  it('without preset, defaults to VERIFIED (ready for adjudication)', () => {
    const component = makeComponent();
    expect(component.statusFilter).toBe('VERIFIED');
    expect(claims.listPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      status: 'VERIFIED',
    }));
  });

  it('presetClaimType route data forwards claim_type filter on every fetch', () => {
    const component = makeComponent({ presetClaimType: 'drug' });
    expect(component.presetClaimType).toBe('drug');
    expect(claims.listPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      claimType: 'drug',
    }));
  });

  it('onPageChange re-issues the paginated request for the new page', () => {
    const component = makeComponent({ presetStatus: 'VERIFIED' });
    claims.listPaged.calls.reset();

    component.onPageChange(3);

    expect(component.page).toBe(3);
    expect(claims.listPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      page: 2,
    }));
  });

  it('onSearchChange resets to page 1 and forwards q', () => {
    const component = makeComponent({ presetStatus: 'VERIFIED' });
    component.page = 5;
    claims.listPaged.calls.reset();

    component.onSearchChange('alice');

    expect(component.page).toBe(1);
    expect(claims.listPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      q: 'alice',
      page: 0,
    }));
  });

  it('onSortChange forwards sort key + direction, resets to page 1', () => {
    const component = makeComponent({ presetStatus: 'VERIFIED' });
    component.page = 2;
    claims.listPaged.calls.reset();

    component.onSortChange({ key: 'memberName', direction: 'asc' });

    expect(component.sortKey).toBe('memberName');
    expect(component.sortDirection).toBe('asc');
    expect(component.page).toBe(1);
    expect(claims.listPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      sortKey: 'memberName',
      sortDirection: 'asc',
      page: 0,
    }));
  });

  it('hydrates rows + totals from the envelope', () => {
    claims.listPaged.and.returnValue(of(rowPage()));
    const component = makeComponent({ presetStatus: 'VERIFIED' });

    expect(component.rows.length).toBe(1);
    expect(component.rows[0].memberName).toBe('Alice Ndlovu');
    expect(component.rows[0].providerName).toBe('Harare Clinic');
    expect(component.totalCount).toBe(1);
    expect(component.totalPages).toBe(1);
  });
});
