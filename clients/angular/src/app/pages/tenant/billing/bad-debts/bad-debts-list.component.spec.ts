import { BehaviorSubject, Observable, of, throwError } from 'rxjs';
import { fakeAsync, tick } from '@angular/core/testing';
import { BadDebtsListComponent } from './bad-debts-list.component';
import { BalanceService, CreditorRow, PageResponse } from '../../../../core/services/balance.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';

/**
 * Component-level spec for BadDebtsListComponent. Drives the class
 * directly (no TestBed) so we can pin the interesting bits — tab
 * derivation per membership model, page reset on filter change, search
 * debounce, and the export-download filename shape — without paying the
 * standalone-component TestBed compilation cost. The template-level
 * rendering is not the subject of these tests; the observable business
 * logic on the class is.
 */
describe('BadDebtsListComponent', () => {
  let balance: jasmine.SpyObj<BalanceService>;
  let currency: jasmine.SpyObj<CurrencyService>;
  let tenant: TenantService & { tenant$: BehaviorSubject<any> };
  let toast: jasmine.SpyObj<ToastService>;
  let component: BadDebtsListComponent;

  const emptyPage: PageResponse<CreditorRow> = {
    content: [], total: 0, page: 0, size: 20, totalPages: 1,
  };
  const usdCfg: TenantCurrencyConfig = {
    id: 'c1', tenantId: 't1', currencyCode: 'USD',
    isDefault: true, isActive: true, isBillingCurrency: true,
    isClaimsCurrency: false, isPaymentCurrency: false, exchangeRateSource: 'MANUAL',
  };

  beforeEach(() => {
    balance = jasmine.createSpyObj<BalanceService>('BalanceService',
      ['listBadDebts', 'exportBadDebtsExcel']);
    balance.listBadDebts.and.returnValue(of(emptyPage));

    currency = jasmine.createSpyObj<CurrencyService>('CurrencyService', ['listForTenant']);
    currency.listForTenant.and.returnValue(of([usdCfg]));

    // TenantService is stateful enough (BehaviorSubject) that a full
    // spy is awkward — hand-roll a small stand-in with the two members
    // the component reads. tenant$ is exposed so tests can flip the
    // membershipModel and re-trigger the subscription.
    const tenant$ = new BehaviorSubject<any>({ id: 't1', membershipModel: 'BOTH' });
    tenant = {
      tenant$,
      getTenantId: () => 't1',
    } as unknown as TenantService & { tenant$: BehaviorSubject<any> };

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['error', 'success']);

    component = new BadDebtsListComponent(balance, currency, tenant, toast);
  });

  afterEach(() => component.ngOnDestroy());

  // ------------------------------------------------------------------
  // rebuildTabs — per membership model
  // ------------------------------------------------------------------

  describe('rebuildTabs (via tenant$ subscription)', () => {
    it('BOTH model renders three tabs and defaults activeTab to null (All)', () => {
      component.ngOnInit();
      expect(component.showTabs).toBeTrue();
      expect(component.tabs.map(t => t.value)).toEqual([null, 'MEMBER', 'GROUP']);
      expect(component.activeTab).toBeNull();
    });

    it('INDIVIDUAL_ONLY hides tabs and pins subject-type filter to MEMBER', () => {
      // Silences an empty Individuals tab for a tenant that only bills
      // individuals — pinning at MEMBER means the server-side filter
      // matches the tenant's actual data model.
      tenant.tenant$.next({ id: 't1', membershipModel: 'INDIVIDUAL_ONLY' });
      component.ngOnInit();
      expect(component.showTabs).toBeFalse();
      expect(component.tabs).toEqual([]);
      expect(component.activeTab).toBe('MEMBER');
    });

    it('GROUP_ONLY hides tabs and pins subject-type filter to GROUP', () => {
      tenant.tenant$.next({ id: 't1', membershipModel: 'GROUP_ONLY' });
      component.ngOnInit();
      expect(component.showTabs).toBeFalse();
      expect(component.tabs).toEqual([]);
      expect(component.activeTab).toBe('GROUP');
    });

    it('falls back to BOTH when membershipModel is undefined', () => {
      // A tenant record without membershipModel is a data-consistency
      // edge case (older tenants pre-V043). Falling back to BOTH is the
      // safe default — the operator sees everything rather than a
      // silently-filtered subset.
      tenant.tenant$.next({ id: 't1' });
      component.ngOnInit();
      expect(component.showTabs).toBeTrue();
      expect(component.activeTab).toBeNull();
    });
  });

  // ------------------------------------------------------------------
  // Page-reset invariants — every filter change resets to page 1 so
  // the operator never lands on an out-of-bounds page after narrowing
  // the filter.
  // ------------------------------------------------------------------

  describe('page-reset invariants', () => {
    beforeEach(() => component.ngOnInit());

    it('selectTab resets page to 1 and reflects the new filter in the request', () => {
      component.page = 5;
      balance.listBadDebts.calls.reset();

      component.selectTab('GROUP');

      expect(component.page).toBe(1);
      expect(component.activeTab).toBe('GROUP');
      // Server call uses page-1 (0-indexed) and GROUP subjectType.
      expect(balance.listBadDebts).toHaveBeenCalledWith('USD', 'GROUP', undefined, 0, 20);
    });

    it('selectTab does nothing when the same tab is clicked twice', () => {
      // Regression guard: no-op click on the active tab must not
      // trigger a redundant server fetch.
      component.selectTab(null);
      balance.listBadDebts.calls.reset();

      component.selectTab(null);

      expect(balance.listBadDebts).not.toHaveBeenCalled();
    });

    it('onCurrencyChange resets page to 1', () => {
      component.page = 3;
      balance.listBadDebts.calls.reset();

      component.onCurrencyChange();

      expect(component.page).toBe(1);
      // activeTab === null becomes undefined at the service boundary
      // (via `activeTab ?? undefined`) so params match the "All" flavour.
      expect(balance.listBadDebts).toHaveBeenCalledWith('USD', undefined, undefined, 0, 20);
    });

    it('onPageChange updates page without resetting activeTab or searchTerm', () => {
      component.searchTerm = 'Acme';
      component.activeTab = 'GROUP';
      balance.listBadDebts.calls.reset();

      component.onPageChange(4);

      expect(component.page).toBe(4);
      expect(balance.listBadDebts).toHaveBeenCalledWith('USD', 'GROUP', 'Acme', 3, 20);
    });
  });

  // ------------------------------------------------------------------
  // Search debounce — 400 ms window before the server sees the query.
  // A regression here would fire one request per keystroke.
  // ------------------------------------------------------------------

  describe('search debounce', () => {
    it('waits 400ms before triggering a fetch and resets page to 1', fakeAsync(() => {
      component.ngOnInit();
      component.page = 6;
      balance.listBadDebts.calls.reset();

      component.onSearch('acm');
      component.onSearch('acme');
      tick(200);
      expect(balance.listBadDebts).not.toHaveBeenCalled();

      tick(200);   // total 400ms — debounce should now fire
      expect(component.page).toBe(1);
      expect(balance.listBadDebts).toHaveBeenCalledWith('USD', undefined, 'acme', 0, 20);
    }));

    it('deduplicates identical consecutive searches', fakeAsync(() => {
      component.ngOnInit();
      balance.listBadDebts.calls.reset();

      component.onSearch('acme');
      tick(400);
      expect(balance.listBadDebts).toHaveBeenCalledTimes(1);

      component.onSearch('acme');   // same term — distinctUntilChanged suppresses
      tick(400);
      expect(balance.listBadDebts).toHaveBeenCalledTimes(1);
    }));
  });

  // ------------------------------------------------------------------
  // exportExcel — download filename shape + double-click guard.
  // ------------------------------------------------------------------

  describe('exportExcel', () => {
    const blob = new Blob(['fake'], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });

    beforeEach(() => {
      balance.exportBadDebtsExcel.and.returnValue(of(blob));
      component.ngOnInit();
    });

    it('does nothing when no currency is selected', () => {
      component.selectedCurrency = '';

      component.exportExcel();

      expect(balance.exportBadDebtsExcel).not.toHaveBeenCalled();
      expect(component.exporting).toBeFalse();
    });

    it('guards against double-click by refusing while exporting=true', () => {
      // Never-resolving Observable so exporting stays true.
      const pending = new Observable<Blob>(() => { /* no emission */ });
      balance.exportBadDebtsExcel.and.returnValue(pending);

      component.exportExcel();
      expect(component.exporting).toBeTrue();
      balance.exportBadDebtsExcel.calls.reset();

      component.exportExcel();   // second click while first is in flight

      expect(balance.exportBadDebtsExcel).not.toHaveBeenCalled();
    });

    it('produces a filename of shape bad-debts-<currency>-<tab-slug>-<YYYY-MM-DD>.xlsx', () => {
      component.activeTab = 'GROUP';

      const anchor = spyOnAnchor();

      component.exportExcel();

      expect(anchor.download).toMatch(
        new RegExp(`^bad-debts-USD-group-\\d{4}-\\d{2}-\\d{2}\\.xlsx$`),
      );
      expect(component.exporting).toBeFalse();
    });

    it('omits the tab-slug when activeTab is null (All tab)', () => {
      component.activeTab = null;

      const anchor = spyOnAnchor();

      component.exportExcel();

      expect(anchor.download).toMatch(
        new RegExp(`^bad-debts-USD-\\d{4}-\\d{2}-\\d{2}\\.xlsx$`),
      );
    });

    it('surfaces a toast on export failure and clears the exporting flag', () => {
      balance.exportBadDebtsExcel.and.returnValue(
        throwError(() => ({ error: { detail: 'Something broke' } })),
      );

      component.exportExcel();

      expect(toast.error).toHaveBeenCalledWith('Something broke');
      expect(component.exporting).toBeFalse();
    });
  });

  /**
   * Replaces document.createElement('a') with a stub that captures the
   * href / download attribute writes. Returns the stub so the test can
   * assert against its `.download` value. Restores in afterEach via the
   * outer scope so the DOM cleans up between specs.
   */
  function spyOnAnchor(): { download: string; href: string; click: jasmine.Spy } {
    const stub = {
      download: '',
      href: '',
      click: jasmine.createSpy('click'),
    };
    spyOn(document, 'createElement').and.callFake((tag: string) => {
      if (tag === 'a') return stub as unknown as HTMLElement;
      // Fall through to a real element for anything else the test triggers.
      return document.createElementNS('http://www.w3.org/1999/xhtml', tag) as unknown as HTMLElement;
    });
    spyOn(document.body, 'appendChild').and.stub();
    spyOn(document.body, 'removeChild').and.stub();
    spyOn(URL, 'createObjectURL').and.returnValue('blob:mock');
    spyOn(URL, 'revokeObjectURL').and.stub();
    return stub;
  }
});
