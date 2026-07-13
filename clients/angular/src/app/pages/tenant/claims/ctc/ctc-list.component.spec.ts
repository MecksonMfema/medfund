import { of } from 'rxjs';
import { ActivatedRoute } from '@angular/router';
import { CtcListComponent } from './ctc-list.component';
import {
  CtcPaymentRow,
  FinancePageResponse,
  FinanceService,
} from '../../../../core/services/finance.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

class StubActivatedRoute {
  constructor(public snapshot: { data: Record<string, unknown> }) {}
}

/**
 * Guards the server-side pagination contract on the CTC list. The
 * previous incarnation hydrated every row and did a client-side filter
 * over raw UUIDs; this spec makes sure a well-meaning revert can't put
 * that shape back.
 */
describe('CtcListComponent', () => {
  let finance: jasmine.SpyObj<FinanceService>;
  let confirm: jasmine.SpyObj<ConfirmService>;
  let toast: jasmine.SpyObj<ToastService>;

  const emptyPage = (): FinancePageResponse<CtcPaymentRow> => ({
    content: [], total: 0, page: 0, size: 50, totalPages: 1,
  });

  const onePage = (): FinancePageResponse<CtcPaymentRow> => ({
    content: [{
      id: 'ctc-1', groupId: 'g1', groupName: 'Acme Ltd',
      amount: '500.00', currencyCode: 'USD', committed: false,
      createdAt: '2026-07-01T00:00:00Z',
    }],
    total: 1, page: 0, size: 50, totalPages: 1,
  });

  function makeComponent(committed = false): CtcListComponent {
    const route = new StubActivatedRoute({ data: { committed } }) as unknown as ActivatedRoute;
    const c = new CtcListComponent(finance, confirm, toast, route);
    c.ngOnInit();
    return c;
  }

  beforeEach(() => {
    finance = jasmine.createSpyObj<FinanceService>('FinanceService', [
      'listCtcPaymentsPaged', 'commitCtcPayment',
    ]);
    finance.listCtcPaymentsPaged.and.returnValue(of(emptyPage()));
    confirm = jasmine.createSpyObj<ConfirmService>('ConfirmService', ['ask']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
  });

  it('pending route asks the server for committed=false', () => {
    const component = makeComponent(false);
    expect(component.committed).toBeFalse();
    expect(finance.listCtcPaymentsPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      committed: false,
      page: 0,
      size: 50,
      sortKey: 'createdAt',
      sortDirection: 'desc',
    }));
  });

  it('committed route asks the server for committed=true', () => {
    const component = makeComponent(true);
    expect(component.committed).toBeTrue();
    expect(finance.listCtcPaymentsPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      committed: true,
    }));
  });

  it('onPageChange re-issues the paginated request for the new page', () => {
    const component = makeComponent(false);
    finance.listCtcPaymentsPaged.calls.reset();

    component.onPageChange(3);

    expect(component.page).toBe(3);
    expect(finance.listCtcPaymentsPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      page: 2,
    }));
  });

  it('onSearchChange resets to page 1 and forwards q', () => {
    const component = makeComponent(false);
    component.page = 4;
    finance.listCtcPaymentsPaged.calls.reset();

    component.onSearchChange('acme');

    expect(component.page).toBe(1);
    expect(finance.listCtcPaymentsPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      q: 'acme',
      page: 0,
    }));
  });

  it('onSortChange forwards sort key + direction, resets to page 1', () => {
    const component = makeComponent(false);
    component.page = 2;
    finance.listCtcPaymentsPaged.calls.reset();

    component.onSortChange({ key: 'amount', direction: 'asc' });

    expect(component.sortKey).toBe('amount');
    expect(component.sortDirection).toBe('asc');
    expect(component.page).toBe(1);
    expect(finance.listCtcPaymentsPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      sortKey: 'amount',
      sortDirection: 'asc',
    }));
  });

  it('hydrates rows + totals from the envelope', () => {
    finance.listCtcPaymentsPaged.and.returnValue(of(onePage()));
    const component = makeComponent(false);

    expect(component.rows.length).toBe(1);
    expect(component.rows[0].groupName).toBe('Acme Ltd');
    expect(component.totalCount).toBe(1);
  });
});
