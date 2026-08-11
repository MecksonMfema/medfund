import { of } from 'rxjs';
import { ActivatedRoute } from '@angular/router';
import { TaxWithheldListComponent } from './tax-withheld-list.component';
import {
  FinancePageResponse,
  FinanceService,
  NoteRow,
  ReportResponse,
} from '../../../../core/services/finance.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

class StubActivatedRoute {
  constructor(public snapshot: { data: Record<string, unknown> }) {}
}

/**
 * Guards the "always pin noteType=TAX_WITHHELD" contract on the
 * tax-withheld list. V074 renamed adjustments → notes and split the
 * discriminator into direction + noteType; TAX_WITHHELD is now a
 * noteType (with implicit direction=DEBIT). The list still pins the
 * filter server-side — a regression here would reload every note ever
 * posted.
 */
describe('TaxWithheldListComponent', () => {
  let finance: jasmine.SpyObj<FinanceService>;
  let toast: jasmine.SpyObj<ToastService>;

  const emptyPage = (): FinancePageResponse<NoteRow> => ({
    content: [], total: 0, page: 0, size: 50, totalPages: 1,
  });

  const emptyEnvelope = (): ReportResponse<FinancePageResponse<NoteRow>> => ({
    reportKey: 'NOTES',
    period: null,
    reportingCurrency: 'USD',
    data: emptyPage(),
    perCurrency: {},
    fxRates: {},
    warnings: [],
    generatedAt: '2026-08-11T00:00:00Z',
  });

  function makeComponent(data: Record<string, unknown> = {}): TaxWithheldListComponent {
    const route = new StubActivatedRoute({ data }) as unknown as ActivatedRoute;
    const c = new TaxWithheldListComponent(finance, toast, route);
    c.ngOnInit();
    return c;
  }

  beforeEach(() => {
    finance = jasmine.createSpyObj<FinanceService>('FinanceService', ['listNotesPaged']);
    finance.listNotesPaged.and.returnValue(of(emptyEnvelope()));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['error']);
  });

  it('ngOnInit fires a paginated request pinned to TAX_WITHHELD', () => {
    const component = makeComponent();
    expect(component.variant).toBe('medical');
    expect(finance.listNotesPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      noteType: 'TAX_WITHHELD',
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
    finance.listNotesPaged.calls.reset();

    component.onStatusChange();

    expect(component.page).toBe(1);
    expect(finance.listNotesPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      noteType: 'TAX_WITHHELD',
      status: 'approved',
      page: 0,
    }));
  });

  it('onSearchChange resets to page 1 and forwards q', () => {
    const component = makeComponent();
    component.page = 3;
    finance.listNotesPaged.calls.reset();

    component.onSearchChange('DN-000001');

    expect(component.page).toBe(1);
    expect(finance.listNotesPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      q: 'DN-000001',
      page: 0,
    }));
  });

  it('onSortChange forwards sort key + direction, resets to page 1', () => {
    const component = makeComponent();
    component.page = 5;
    finance.listNotesPaged.calls.reset();

    component.onSortChange({ key: 'amount', direction: 'asc' });

    expect(component.sortKey).toBe('amount');
    expect(component.sortDirection).toBe('asc');
    expect(component.page).toBe(1);
    expect(finance.listNotesPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      sortKey: 'amount',
      sortDirection: 'asc',
    }));
  });
});
