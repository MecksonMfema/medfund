import { of, throwError } from 'rxjs';
import { ActivatedRoute } from '@angular/router';
import { TariffCodesListComponent } from './tariff-codes-list.component';
import {
  ClaimsConfigService,
  PageResponse,
  TariffCodeRow,
} from '../../../../core/services/claims-config.service';
import { TariffCategoriesService } from '../../../../core/services/tariff-categories.service';

/**
 * Guards the two contracts this list depends on:
 * <ol>
 *   <li>V063 — the add form's category dropdown is required. Silent
 *       regression (dropdown becomes optional again) would allow tariffs
 *       without a category to be created, which the resolver + Stage 3
 *       would then reject as R03-TARIFF_UNMAPPED at claim time.</li>
 *   <li>The AHFOZ March schedule holds ~5,000 codes; the page must never
 *       fall back to fetching them all in one shot. Pagination, search,
 *       and sort all round-trip to the server. Regression here would
 *       reintroduce the client-side join that made the page feel slow.</li>
 * </ol>
 */
class StubActivatedRoute {
  snapshot = { paramMap: { get: (_: string) => 'schedule-1' } };
}

describe('TariffCodesListComponent', () => {
  let config: jasmine.SpyObj<ClaimsConfigService>;
  let categoriesSvc: jasmine.SpyObj<TariffCategoriesService>;
  let component: TariffCodesListComponent;

  const emptyPage = (): PageResponse<TariffCodeRow> => ({
    content: [], total: 0, page: 0, size: 50, totalPages: 1,
  });

  const onePage = (): PageResponse<TariffCodeRow> => ({
    content: [{
      id: 't1', scheduleId: 'schedule-1', code: 'TC001',
      description: 'Consult', categoryId: 'cat-1',
      categoryLabel: 'Consultation',
      unitPrice: '250.00', currencyCode: 'USD', requiresPreAuth: false,
    }],
    total: 1, page: 0, size: 50, totalPages: 1,
  });

  beforeEach(() => {
    config = jasmine.createSpyObj<ClaimsConfigService>(
      'ClaimsConfigService', ['getSchedule', 'listCodesPaged', 'createCode'],
    );
    categoriesSvc = jasmine.createSpyObj<TariffCategoriesService>(
      'TariffCategoriesService', ['list'],
    );

    config.getSchedule.and.returnValue(of({
      id: 'schedule-1', name: 'Std', effectiveDate: '2026-01-01', status: 'active',
    } as any));
    config.listCodesPaged.and.returnValue(of(emptyPage()));
    categoriesSvc.list.and.returnValue(of([
      { id: 'cat-1', code: 'CONSULTATION', label: 'Consultation', isCapOnly: false, isActive: true, sortOrder: 10 },
    ]));
    config.createCode.and.returnValue(of({} as any));

    component = new TariffCodesListComponent(
      config, categoriesSvc, new StubActivatedRoute() as unknown as ActivatedRoute,
    );
    component.ngOnInit();
  });

  // ── Server-side pagination contract ───────────────────────────────────
  it('ngOnInit fires a paginated codes request scoped to the current schedule', () => {
    expect(config.listCodesPaged).toHaveBeenCalledWith(jasmine.objectContaining({
      scheduleId: 'schedule-1',
      page: 0,
      size: 50,
      sortKey: 'code',
      sortDirection: 'asc',
    }));
  });

  it('onPageChange re-issues the paginated request for the new page', () => {
    config.listCodesPaged.calls.reset();
    component.onPageChange(3);

    expect(component.page).toBe(3);
    expect(config.listCodesPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      page: 2,  // zero-based on the wire
      scheduleId: 'schedule-1',
    }));
  });

  it('onSearchChange resets to page 1 and forwards the query string', () => {
    component.page = 4;
    config.listCodesPaged.calls.reset();

    component.onSearchChange('MRI');

    expect(component.page).toBe(1);
    expect(config.listCodesPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      q: 'MRI',
      page: 0,
    }));
  });

  it('onSortChange forwards the key + direction and resets to page 1', () => {
    component.page = 2;
    config.listCodesPaged.calls.reset();

    component.onSortChange({ key: 'unitPrice', direction: 'desc' });

    expect(component.sortKey).toBe('unitPrice');
    expect(component.sortDirection).toBe('desc');
    expect(component.page).toBe(1);
    expect(config.listCodesPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      sortKey: 'unitPrice',
      sortDirection: 'desc',
      page: 0,
    }));
  });

  it('renders totalCount + totalPages from the server envelope', () => {
    config.listCodesPaged.and.returnValue(of(onePage()));
    component.fetchPage();

    expect(component.rows.length).toBe(1);
    expect(component.rows[0].categoryLabel).toBe('Consultation');
    expect(component.totalCount).toBe(1);
    expect(component.totalPages).toBe(1);
  });

  // ── V063 required-category contract ───────────────────────────────────
  it('submitDraft with a blank categoryId sets an error and skips the service', () => {
    component.draft = {
      scheduleId: 'schedule-1',
      code: 'TC001',
      description: 'Consult',
      categoryId: '',
      unitPrice: '250.00',
      currencyCode: 'USD',
      requiresPreAuth: false,
    };

    component.submitDraft();

    expect(config.createCode).not.toHaveBeenCalled();
    expect(component.errorMessage).toBe('Category is required');
    expect(component.saving).toBeFalse();
  });

  it('submitDraft with a valid categoryId calls createCode and reloads page 1', () => {
    component.draft = {
      scheduleId: 'schedule-1',
      code: 'TC001',
      description: 'Consult',
      categoryId: 'cat-1',
      unitPrice: '250.00',
      currencyCode: 'USD',
      requiresPreAuth: false,
    };
    component.page = 4;
    config.listCodesPaged.calls.reset();

    component.submitDraft();

    expect(config.createCode).toHaveBeenCalledWith(jasmine.objectContaining({
      scheduleId: 'schedule-1',
      code: 'TC001',
      categoryId: 'cat-1',
    }));
    // After a successful create the operator lands back on page 1 so the
    // new row is visible under the default sort.
    expect(component.page).toBe(1);
    expect(config.listCodesPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({ page: 0 }));
  });

  // ── Category lazy-load ────────────────────────────────────────────────
  it('does not fetch categories until the add form is opened', () => {
    expect(categoriesSvc.list).not.toHaveBeenCalled();

    component.toggleForm();

    expect(categoriesSvc.list).toHaveBeenCalledOnceWith(true);
    expect(component.categoryOptions.length).toBe(1);
  });

  it('a categories outage does not blank the codes table', () => {
    categoriesSvc.list.and.returnValue(throwError(() => new Error('boom')));

    component.toggleForm();

    // Rows still show; the operator just gets a banner steering them away
    // from creating a code until the catalogue comes back.
    expect(component.rows).toEqual([]);
    expect(component.errorMessage).toContain('Categories catalogue');
  });
});
