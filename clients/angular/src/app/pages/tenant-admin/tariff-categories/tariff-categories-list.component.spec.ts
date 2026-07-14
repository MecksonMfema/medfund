import { of, throwError } from 'rxjs';
import { TariffCategoriesListComponent } from './tariff-categories-list.component';
import {
  TariffCategoriesService,
  TariffCategory,
  TariffCategoryPageResponse,
} from '../../../core/services/tariff-categories.service';

/**
 * V063 tenant-admin catalogue page. Direct-instantiation tests (no
 * TestBed). Server-side paginated after the platform-wide pagination
 * sweep.
 */
describe('TariffCategoriesListComponent (V063 admin catalogue)', () => {
  let svc: jasmine.SpyObj<TariffCategoriesService>;
  let component: TariffCategoriesListComponent;

  const existingRow: TariffCategory = {
    id: 'cat-1', code: 'CONSULTATION', label: 'Consultation',
    isCapOnly: false, isActive: true, sortOrder: 10,
  };

  const singleRowPage = (): TariffCategoryPageResponse => ({
    content: [existingRow], total: 1, page: 0, size: 50, totalPages: 1,
  });

  beforeEach(() => {
    svc = jasmine.createSpyObj<TariffCategoriesService>(
      'TariffCategoriesService',
      ['list', 'listPaged', 'create', 'update', 'deactivate'],
    );
    svc.listPaged.and.returnValue(of(singleRowPage()));
    component = new TariffCategoriesListComponent(svc);
  });

  it('startCreate_resetsDraftAndShowsForm', () => {
    component.draft = { code: 'STALE', label: 'stale', isCapOnly: true, isActive: false, sortOrder: 42 };
    component.showForm = false;

    component.startCreate();

    expect(component.showForm).toBeTrue();
    expect(component.draft.code).toBe('');
    expect(component.draft.label).toBe('');
    expect(component.draft.isCapOnly).toBeFalse();
    expect(component.draft.isActive).toBeTrue();
    expect(component.draft.sortOrder).toBe(0);
    expect(component.draft.id).toBeUndefined();
  });

  it('startEdit_populatesDraftFromRow', () => {
    component.startEdit(existingRow);

    expect(component.showForm).toBeTrue();
    expect(component.draft.id).toBe('cat-1');
    expect(component.draft.code).toBe('CONSULTATION');
    expect(component.draft.label).toBe('Consultation');
    expect(component.draft.isCapOnly).toBeFalse();
    expect(component.draft.sortOrder).toBe(10);
  });

  it('save_blankCodeOrLabel_setsErrorAndSkipsService', () => {
    component.draft = { code: '', label: 'X', isCapOnly: false, isActive: true };

    component.save();

    expect(component.errorMessage).toContain('required');
    expect(svc.create).not.toHaveBeenCalled();
    expect(svc.update).not.toHaveBeenCalled();
    expect(component.saving).toBeFalse();
  });

  it('save_valid_callsCreateThenReloads', () => {
    svc.create.and.returnValue(of(existingRow));
    svc.listPaged.calls.reset();
    component.draft = { code: 'PHYSIO', label: 'Physiotherapy', isCapOnly: false, isActive: true };

    component.save();

    expect(svc.create).toHaveBeenCalledWith(jasmine.objectContaining({
      code: 'PHYSIO', label: 'Physiotherapy',
    }));
    expect(component.showForm).toBeFalse();
    expect(component.successMessage).toContain('saved');
    // Reload fires after successful save — server-paginated.
    expect(svc.listPaged).toHaveBeenCalledTimes(1);
    expect(component.rows.length).toBe(1);
  });

  it('deactivate_confirmed_callsService', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    svc.deactivate.and.returnValue(of(void 0));

    component.deactivate(existingRow);

    expect(svc.deactivate).toHaveBeenCalledWith('cat-1');
  });

  it('deactivate_declined_doesNothing', () => {
    spyOn(window, 'confirm').and.returnValue(false);

    component.deactivate(existingRow);

    expect(svc.deactivate).not.toHaveBeenCalled();
  });

  it('save_serverError_surfacesMessage', () => {
    svc.create.and.returnValue(throwError(() => ({ error: { detail: 'Duplicate code' } })));
    component.draft = { code: 'DUP', label: 'Duplicate', isCapOnly: false, isActive: true };

    component.save();

    expect(component.saving).toBeFalse();
    expect(component.errorMessage).toBe('Duplicate code');
  });

  // ── Server-side pagination contract ─────────────────────────────────

  it('fetchPage forwards default sort + search params', () => {
    svc.listPaged.calls.reset();
    component.fetchPage();

    expect(svc.listPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      page: 0,
      size: 50,
      sortKey: 'sortOrder',
      sortDirection: 'asc',
    }));
  });

  it('onSearchChange resets to page 1 and forwards q', () => {
    component.page = 4;
    svc.listPaged.calls.reset();

    component.onSearchChange('cons');

    expect(component.page).toBe(1);
    expect(svc.listPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      q: 'cons',
      page: 0,
    }));
  });
});
