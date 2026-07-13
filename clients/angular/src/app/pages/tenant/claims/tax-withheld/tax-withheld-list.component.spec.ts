import { of } from 'rxjs';
import { ActivatedRoute } from '@angular/router';
import { TaxWithheldListComponent } from './tax-withheld-list.component';
import {
  AdjustmentRow,
  FinancePageResponse,
  FinanceService,
} from '../../../../core/services/finance.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

class StubActivatedRoute {
  constructor(public snapshot: { data: Record<string, unknown> }) {}
}

/**
 * Guards the "always pin adjustmentType=TAX_WITHHELD" contract on the
 * tax-withheld list. The previous incarnation did a client-side status
 * fan-out over 4 statuses and filtered client-side; the server now
 * takes both filter axes, so a regression here would silently reload
 * every adjustment ever posted.
 */
describe('TaxWithheldListComponent', () => {
  let finance: jasmine.SpyObj<FinanceService>;
  let toast: jasmine.SpyObj<ToastService>;

  const emptyPage = (): FinancePageResponse<AdjustmentRow> => ({
    content: [], total: 0, page: 0, size: 50, totalPages: 1,
  });

  function makeComponent(data: Record<string, unknown> = {}): TaxWithheldListComponent {
    const route = new StubActivatedRoute({ data }) as unknown as ActivatedRoute;
    const c = new TaxWithheldListComponent(finance, toast, route);
    c.ngOnInit();
    return c;
  }

  beforeEach(() => {
    finance = jasmine.createSpyObj<FinanceService>('FinanceService', ['listAdjustmentsPaged']);
    finance.listAdjustmentsPaged.and.returnValue(of(emptyPage()));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['error']);
  });

  it('ngOnInit fires a paginated request pinned to TAX_WITHHELD', () => {
    const component = makeComponent();
    expect(component.variant).toBe('medical');
    expect(finance.listAdjustmentsPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      adjustmentType: 'TAX_WITHHELD',
      page: 0,
      size: 50,
      sortKey: 'createdAt',
      sortDirection: 'desc',
    }));
  });

  it('picks up variant=drug from route data for the drug-claim heading', () => {
    const component = makeComponent({ variant: 'drug' });
    expect(component.variant).toBe('drug');
    expect(component.pageTitle).toBe('Tax-withheld drug claims');
  });

  it('onStatusChange forwards the status filter and resets to page 1', () => {
    const component = makeComponent();
    component.page = 4;
    component.statusFilter = 'approved';
    finance.listAdjustmentsPaged.calls.reset();

    component.onStatusChange();

    expect(component.page).toBe(1);
    expect(finance.listAdjustmentsPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      adjustmentType: 'TAX_WITHHELD',
      status: 'approved',
      page: 0,
    }));
  });

  it('onSearchChange resets to page 1 and forwards q', () => {
    const component = makeComponent();
    component.page = 3;
    finance.listAdjustmentsPaged.calls.reset();

    component.onSearchChange('ADJ-001');

    expect(component.page).toBe(1);
    expect(finance.listAdjustmentsPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      q: 'ADJ-001',
      page: 0,
    }));
  });

  it('onSortChange forwards sort key + direction, resets to page 1', () => {
    const component = makeComponent();
    component.page = 5;
    finance.listAdjustmentsPaged.calls.reset();

    component.onSortChange({ key: 'amount', direction: 'asc' });

    expect(component.sortKey).toBe('amount');
    expect(component.sortDirection).toBe('asc');
    expect(component.page).toBe(1);
    expect(finance.listAdjustmentsPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      sortKey: 'amount',
      sortDirection: 'asc',
    }));
  });
});
