import { BehaviorSubject, Observable, of, throwError } from 'rxjs';
import { fakeAsync, tick } from '@angular/core/testing';
import { ChargePreviewComponent } from './charge-preview.component';
import {
  ChargePreviewResponse,
  ContributionsService,
  GroupOption,
} from '../../../../core/services/contributions.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { MembersService, Member } from '../../../../core/services/members.service';
import { TenantService } from '../../../../core/services/tenant.service';

/**
 * Component-level spec for ChargePreviewComponent. Drives the class
 * directly (no TestBed) — the class-side logic is what tends to
 * regress (tab derivation, auto-fetch invariants, grouped-member
 * filter). Template rendering isn't the subject.
 */
describe('ChargePreviewComponent', () => {
  let contributions: jasmine.SpyObj<ContributionsService>;
  let members: jasmine.SpyObj<MembersService>;
  let currency: jasmine.SpyObj<CurrencyService>;
  let tenant: TenantService & { tenant$: BehaviorSubject<any> };
  let component: ChargePreviewComponent;

  const usdCfg: TenantCurrencyConfig = {
    id: 'c1', tenantId: 't1', currencyCode: 'USD',
    isDefault: true, isActive: true, isBillingCurrency: true,
    isClaimsCurrency: false, isPaymentCurrency: false, exchangeRateSource: 'MANUAL',
  };

  const emptyResponse: ChargePreviewResponse = {
    subjectType: 'GROUP', subjectId: 'g1', subjectName: 'Acme',
    periodStart: '2026-08-01', periodEnd: '2026-08-31',
    lines: [], totals: {}, excludedTerminating: 0,
    asOf: new Date().toISOString(),
  };

  beforeEach(() => {
    contributions = jasmine.createSpyObj<ContributionsService>('ContributionsService',
      ['searchGroups', 'chargePreview']);
    contributions.searchGroups.and.returnValue(of([]));
    contributions.chargePreview.and.returnValue(of(emptyResponse));

    members = jasmine.createSpyObj<MembersService>('MembersService', ['searchByName']);
    members.searchByName.and.returnValue(of([]));

    currency = jasmine.createSpyObj<CurrencyService>('CurrencyService', ['listForTenant']);
    currency.listForTenant.and.returnValue(of([usdCfg]));

    const tenant$ = new BehaviorSubject<any>({ id: 't1', membershipModel: 'BOTH' });
    tenant = {
      tenant$,
      getTenantId: () => 't1',
    } as unknown as TenantService & { tenant$: BehaviorSubject<any> };

    component = new ChargePreviewComponent(contributions, members, currency, tenant);
  });

  afterEach(() => component.ngOnDestroy());

  // ------------------------------------------------------------------
  // rebuildTabs / configureTabsFrom — per membership model
  // ------------------------------------------------------------------

  describe('tab configuration from membershipModel', () => {
    it('BOTH model shows the tab strip and defaults to GROUP', () => {
      component.ngOnInit();
      expect(component.showTabs).toBeTrue();
      expect(component.activeTab).toBe('GROUP');
    });

    it('INDIVIDUAL_ONLY hides tabs and pins to MEMBER', () => {
      // A tenant that only bills individuals should never see the
      // Group tab — the resolver would return nothing for the group
      // half anyway.
      tenant.tenant$.next({ id: 't1', membershipModel: 'INDIVIDUAL_ONLY' });
      component.ngOnInit();
      expect(component.showTabs).toBeFalse();
      expect(component.activeTab).toBe('MEMBER');
    });

    it('GROUP_ONLY hides tabs and pins to GROUP', () => {
      tenant.tenant$.next({ id: 't1', membershipModel: 'GROUP_ONLY' });
      component.ngOnInit();
      expect(component.showTabs).toBeFalse();
      expect(component.activeTab).toBe('GROUP');
    });
  });

  // ------------------------------------------------------------------
  // Tab switching — must clear the current target + preview because
  // a group id can't stand in as a member id.
  // ------------------------------------------------------------------

  describe('selectTab', () => {
    beforeEach(() => component.ngOnInit());

    it('switching from GROUP to MEMBER clears target + preview', () => {
      component.selectedTarget = { id: 'g1', label: 'Acme' };
      component.targetQuery = 'Acme';
      component.preview = emptyResponse;

      component.selectTab('MEMBER');

      expect(component.activeTab).toBe('MEMBER');
      expect(component.selectedTarget).toBeNull();
      expect(component.targetQuery).toBe('');
      expect(component.preview).toBeNull();
    });

    it('no-op when the same tab is clicked twice', () => {
      component.selectedTarget = { id: 'g1', label: 'Acme' };
      component.selectTab('GROUP');
      // Selection preserved — no clear should have run.
      expect(component.selectedTarget).toEqual({ id: 'g1', label: 'Acme' });
    });
  });

  // ------------------------------------------------------------------
  // Target picking → auto-fetch invariant. Regression here would
  // leave the operator staring at an empty "pick a target" state
  // even after picking one.
  // ------------------------------------------------------------------

  describe('pickTarget triggers fetch', () => {
    beforeEach(() => component.ngOnInit());

    it('calls chargePreview with the picked target + active tab + currency', () => {
      contributions.chargePreview.calls.reset();
      component.selectedCurrency = 'USD';
      component.pickTarget({ id: 'g1', label: 'Acme' });

      expect(contributions.chargePreview).toHaveBeenCalledWith('GROUP', 'g1', 'USD');
      expect(component.selectedTarget).toEqual({ id: 'g1', label: 'Acme' });
    });

    it('passes undefined currency when "All currencies" is selected (empty string)', () => {
      contributions.chargePreview.calls.reset();
      component.selectedCurrency = '';
      component.pickTarget({ id: 'g1', label: 'Acme' });

      expect(contributions.chargePreview).toHaveBeenCalledWith('GROUP', 'g1', undefined);
    });
  });

  // ------------------------------------------------------------------
  // Currency change refetches only when a target is picked — a
  // currency flip with no target would 400 at the server.
  // ------------------------------------------------------------------

  describe('onCurrencyChange', () => {
    beforeEach(() => component.ngOnInit());

    it('does nothing when no target is picked', () => {
      contributions.chargePreview.calls.reset();
      component.selectedTarget = null;

      component.onCurrencyChange();

      expect(contributions.chargePreview).not.toHaveBeenCalled();
    });

    it('refetches when a target is picked', () => {
      contributions.chargePreview.calls.reset();
      component.selectedTarget = { id: 'g1', label: 'Acme' };
      component.selectedCurrency = 'ZAR';

      component.onCurrencyChange();

      expect(contributions.chargePreview).toHaveBeenCalledWith('GROUP', 'g1', 'ZAR');
    });
  });

  // ------------------------------------------------------------------
  // Search routing + grouped-member exclusion. The member half of
  // the picker must drop rows with a groupId — those are billed
  // through the group's liaison, so a per-member charge preview
  // would double-count (feedback_grouped_members_cannot_pay).
  // ------------------------------------------------------------------

  describe('search routing by activeTab', () => {
    it('GROUP tab hits searchGroups and shapes the option', fakeAsync(() => {
      component.ngOnInit();
      const grp: GroupOption = { id: 'g1', name: 'Acme', registrationNumber: 'ACME-1' };
      contributions.searchGroups.and.returnValue(of([grp]));
      component.activeTab = 'GROUP';

      component.targetQuery = 'Ac';
      component.onTargetQueryChange();
      tick(300);

      expect(contributions.searchGroups).toHaveBeenCalledWith('Ac');
      expect(component.targetMatches).toEqual([
        { id: 'g1', label: 'Acme', sublabel: 'ACME-1' },
      ]);
    }));

    it('MEMBER tab hits searchByName and drops grouped members', fakeAsync(() => {
      component.ngOnInit();
      const rows: Member[] = [
        makeMember({ id: 'm1', firstName: 'Alice', lastName: 'A', memberNumber: 'M-1', groupId: null }),
        makeMember({ id: 'm2', firstName: 'Bob',   lastName: 'B', memberNumber: 'M-2', groupId: 'g1' }),
        makeMember({ id: 'm3', firstName: 'Carol', lastName: 'C', memberNumber: 'M-3', groupId: null }),
      ];
      members.searchByName.and.returnValue(of(rows));
      component.activeTab = 'MEMBER';

      component.targetQuery = 'a';
      component.onTargetQueryChange();
      tick(300);

      // Bob dropped — his groupId is set. Alice + Carol survive.
      const ids = component.targetMatches.map(m => m.id);
      expect(ids).toEqual(['m1', 'm3']);
    }));
  });

  // ------------------------------------------------------------------
  // Auto-refresh — 30s tick refetches when a target is picked and
  // no fetch is in flight. A regression that piled up requests would
  // double-charge the endpoint on a tab left open.
  // ------------------------------------------------------------------

  describe('auto-refresh (30s interval)', () => {
    it('refetches on tick when a target is picked and not loading', fakeAsync(() => {
      component.ngOnInit();
      component.selectedTarget = { id: 'g1', label: 'Acme' };
      contributions.chargePreview.calls.reset();

      tick(30_000);

      expect(contributions.chargePreview).toHaveBeenCalledTimes(1);

      // Second tick fires another fetch.
      tick(30_000);
      expect(contributions.chargePreview).toHaveBeenCalledTimes(2);

      component.ngOnDestroy();
    }));

    it('skips the tick when a fetch is already in flight', fakeAsync(() => {
      component.ngOnInit();
      component.selectedTarget = { id: 'g1', label: 'Acme' };
      // Never-resolving observable so loading stays true.
      contributions.chargePreview.and.returnValue(new Observable(() => {}));
      component.fetch();
      expect(component.loading).toBeTrue();
      contributions.chargePreview.calls.reset();

      tick(30_000);

      // Skipped because loading is true.
      expect(contributions.chargePreview).not.toHaveBeenCalled();

      component.ngOnDestroy();
    }));

    it('skips ticks entirely when no target is picked', fakeAsync(() => {
      component.ngOnInit();
      component.selectedTarget = null;
      contributions.chargePreview.calls.reset();

      tick(30_000);
      tick(30_000);

      expect(contributions.chargePreview).not.toHaveBeenCalled();
      component.ngOnDestroy();
    }));
  });

  // ------------------------------------------------------------------
  // Error handling + presentation helpers
  // ------------------------------------------------------------------

  describe('fetch error path', () => {
    beforeEach(() => component.ngOnInit());

    it('surfaces error detail and clears loading + preview', () => {
      contributions.chargePreview.and.returnValue(
        throwError(() => ({ error: { detail: 'nope' } })),
      );
      component.selectedTarget = { id: 'g1', label: 'Acme' };

      component.fetch();

      expect(component.errorMessage).toBe('nope');
      expect(component.preview).toBeNull();
      expect(component.loading).toBeFalse();
    });
  });

  describe('asOfLabel', () => {
    it('renders a HH:MM:SS-shaped time string from an ISO instant', () => {
      // Not asserting the exact digits (locale-dependent) — asserting
      // the shape so we know the formatter isn't returning "Invalid Date".
      const iso = '2026-08-15T12:34:56Z';
      const label = component.asOfLabel(iso);
      expect(label).toMatch(/\d{1,2}[:.]\d{2}[:.]\d{2}/);
    });
  });
});

function makeMember(overrides: Partial<Member> = {}): Member {
  return {
    id: 'm-1', memberNumber: 'M-001',
    firstName: 'Jane', lastName: 'Doe',
    dateOfBirth: '1990-01-01',
    email: 'j@x', phone: '',
    status: 'active', groupId: null, schemeId: null,
    enrollmentDate: '2024-01-01', createdAt: '2024-01-01',
    ...overrides,
  };
}
