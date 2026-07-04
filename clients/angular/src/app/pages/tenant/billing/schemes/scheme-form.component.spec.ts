import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SchemeFormComponent } from './scheme-form.component';
import { ContributionsService, Scheme } from '../../../../core/services/contributions.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { MockTenantService, buildTenant } from '../../../../_test-utils/mock-tenant.service';

function makeCurrencyConfig(overrides: Partial<TenantCurrencyConfig> = {}): TenantCurrencyConfig {
  return {
    id: 'cur-1',
    tenantId: 'tenant-1',
    currencyCode: 'USD',
    isDefault: true,
    isActive: true,
    isBillingCurrency: true,
    isClaimsCurrency: true,
    isPaymentCurrency: true,
    exchangeRateSource: 'manual',
    ...overrides,
  };
}

describe('SchemeFormComponent', () => {
  let fixture: ComponentFixture<SchemeFormComponent>;
  let component: SchemeFormComponent;
  let tenantService: MockTenantService;
  let router: Router;
  let navigateSpy: jasmine.Spy;

  let contributions: jasmine.SpyObj<ContributionsService>;
  let currency: jasmine.SpyObj<CurrencyService>;
  let toast: jasmine.SpyObj<ToastService>;
  let paramId: string | null = null;

  function setup(opts: {
    insuranceLines?: string[],
    paramId?: string | null,
    currencies?: TenantCurrencyConfig[],
  } = {}): void {
    paramId = opts.paramId ?? null;
    tenantService = new MockTenantService(buildTenant({
      id: 'tenant-1',
      insuranceLines: opts.insuranceLines ?? ['HEALTH'],
    }));

    const newScheme: Scheme = {
      id: 'new-id', name: 'New', description: '', schemeType: 'medical_aid',
      effectiveDate: '2026-01-01', endDate: '', currencyCode: 'USD', status: 'active',
    } as Scheme;
    const updated: Scheme = { ...newScheme, id: 'updated' };
    const existing: Scheme = {
      id: 'existing-id', name: 'Existing', description: 'd', schemeType: 'hmo',
      effectiveDate: '2026-01-01', endDate: '', currencyCode: 'USD', status: 'active',
    } as Scheme;

    contributions = jasmine.createSpyObj<ContributionsService>('ContributionsService',
      ['getSchemeById', 'createScheme', 'updateScheme']);
    contributions.createScheme.and.returnValue(of(newScheme));
    contributions.updateScheme.and.returnValue(of(updated));
    contributions.getSchemeById.and.returnValue(of(existing));

    currency = jasmine.createSpyObj<CurrencyService>('CurrencyService', ['listForTenant']);
    currency.listForTenant.and.returnValue(of(opts.currencies ?? [makeCurrencyConfig()]));

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);

    TestBed.configureTestingModule({
      imports: [SchemeFormComponent],
      providers: [
        provideRouter([]),
        { provide: TenantService, useValue: tenantService },
        { provide: ContributionsService, useValue: contributions },
        { provide: CurrencyService, useValue: currency },
        { provide: ToastService, useValue: toast },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap(paramId ? { id: paramId } : {}) },
          },
        },
      ],
    });

    router = TestBed.inject(Router);
    navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);

    fixture = TestBed.createComponent(SchemeFormComponent);
    component = fixture.componentInstance;
  }

  it('shows an error and skips initialization when no tenant is active', () => {
    setup();
    tenantService.clearTenant();
    fixture.detectChanges(); // triggers ngOnInit

    expect(component.errorMessage).toBe('No active tenant context');
    expect(currency.listForTenant).not.toHaveBeenCalled();
  });

  it('populates schemeTypes only from the tenant insurance lines', () => {
    setup({ insuranceLines: ['VEHICLE'] });
    fixture.detectChanges();

    expect(component.schemeTypes.length).toBeGreaterThan(0);
    expect(component.schemeTypes.every(t => t.line === 'VEHICLE')).toBe(true);
    expect(component.form.schemeType).toBe('comprehensive');
  });

  it('groups scheme types by insurance line label in the dropdown when multi-line', () => {
    // The grouped-dropdown behaviour used to expose schemeTypeGroups +
    // schemeTypesForLine helpers; both were folded into a single
    // schemeTypeSelectOptions getter that stamps a `group` label on
    // each option. Assert the group survives round-trip when more than
    // one line is configured (label is the human-readable form —
    // "Life Insurance" — not the raw enum).
    setup({ insuranceLines: ['LIFE', 'HEALTH'] });
    fixture.detectChanges();

    const opts = component.schemeTypeSelectOptions;
    const groups = Array.from(new Set(opts.map(o => o.group).filter(Boolean)));
    // Both lines must appear as group headings; ordering follows the
    // tenant's insuranceLines config.
    expect(groups.length).toBe(2);
    // Every option carries its line's group when multi-line — no
    // orphaned "group: undefined" options.
    expect(opts.every(o => !!o.group)).toBe(true);
  });

  it('omits the group heading on single-line tenants so the dropdown stays clean', () => {
    setup({ insuranceLines: ['HEALTH'] });
    fixture.detectChanges();

    // Single line → no need to visually separate; group is left off so
    // the SelectComponent renders a flat list.
    expect(component.schemeTypeSelectOptions.every(o => o.group === undefined)).toBe(true);
  });

  it('defaults form.currencyCode to the tenant default-currency config', () => {
    setup({
      currencies: [
        makeCurrencyConfig({ currencyCode: 'USD', isDefault: false }),
        makeCurrencyConfig({ id: 'cur-2', currencyCode: 'EUR', isDefault: true }),
      ],
    });
    fixture.detectChanges();

    expect(component.form.currencyCode).toBe('EUR');
  });

  it('filters allowedCurrencies down to billing-active configs', () => {
    setup({
      currencies: [
        makeCurrencyConfig({ currencyCode: 'USD', isActive: true,  isBillingCurrency: true  }),
        makeCurrencyConfig({ id: 'c2', currencyCode: 'ZAR', isActive: true,  isBillingCurrency: false }),
        makeCurrencyConfig({ id: 'c3', currencyCode: 'EUR', isActive: false, isBillingCurrency: true  }),
      ],
    });
    fixture.detectChanges();

    expect(component.allowedCurrencies.map(c => c.currencyCode)).toEqual(['USD']);
  });

  it('loads an existing scheme when navigated with an :id param', () => {
    setup({ paramId: 'existing-id' });
    fixture.detectChanges();

    expect(contributions.getSchemeById).toHaveBeenCalledWith('existing-id');
    expect(component.form.name).toBe('Existing');
    expect(component.form.schemeType).toBe('hmo');
    expect(component.loading).toBe(false);
  });

  it('blocks submit and surfaces an error when name is blank', () => {
    setup();
    fixture.detectChanges();
    component.form.name = '   ';

    component.submit();

    expect(component.errorMessage).toBe('Name is required');
    expect(contributions.createScheme).not.toHaveBeenCalled();
  });

  it('blocks submit and surfaces an error when currency is unset', () => {
    setup({ currencies: [] });
    fixture.detectChanges();
    component.form.name = 'Plan';
    component.form.currencyCode = '';

    component.submit();

    expect(component.errorMessage).toBe('Pick a currency for this scheme');
    expect(contributions.createScheme).not.toHaveBeenCalled();
  });

  it('on create — sends trimmed payload, toasts success, and navigates back', () => {
    setup();
    fixture.detectChanges();
    component.form.name = '  Plan A  ';
    component.form.description = '  desc  ';
    component.form.currencyCode = 'USD';

    component.submit();

    expect(contributions.createScheme).toHaveBeenCalledTimes(1);
    const payload = contributions.createScheme.calls.mostRecent().args[0];
    expect(payload.name).toBe('Plan A');
    expect(payload.description).toBe('desc');
    expect(payload.currencyCode).toBe('USD');
    expect(toast.success).toHaveBeenCalledWith('Scheme created');
    expect(navigateSpy).toHaveBeenCalledWith(['/tenant/billing/schemes']);
  });

  it('on update — calls updateScheme with the route id and toasts updated', () => {
    setup({ paramId: 'existing-id' });
    fixture.detectChanges();
    component.form.name = 'Renamed';

    component.submit();

    expect(contributions.updateScheme).toHaveBeenCalledWith('existing-id', jasmine.objectContaining({
      name: 'Renamed',
    }));
    expect(toast.success).toHaveBeenCalledWith('Scheme updated');
  });
});
