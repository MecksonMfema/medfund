import { of } from 'rxjs';
import { CtcAutoComponent } from './ctc-auto.component';
import {
  CtcAutoConfigService,
  TenantCtcAutoConfig,
} from '../../../../core/services/ctc-auto-config.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { CurrencyService } from '../../../../core/services/currency.service';
import { PermissionService } from '../../../../core/security/permission.service';
import { FinanceService } from '../../../../core/services/finance.service';

/**
 * Guards the auto-CTC config surface:
 *  - GETs on init and hydrates form.
 *  - PUT payload has the right shape (empty cap → null; currency upper-cased).
 *  - "Recent auto-drafts" table is loaded via the paginated CTC endpoint
 *    with systemDrafted=true.
 *  - Save is a no-op when the user lacks finance:configure_auto_ctc.
 */
describe('CtcAutoComponent', () => {
  let ctcAutoConfig: jasmine.SpyObj<CtcAutoConfigService>;
  let tenantService: jasmine.SpyObj<TenantService>;
  let currencyService: jasmine.SpyObj<CurrencyService>;
  let permissionService: jasmine.SpyObj<PermissionService>;
  let finance: jasmine.SpyObj<FinanceService>;

  const disabledConfig = (): TenantCtcAutoConfig => ({
    tenantId: 't1',
    enabled: false,
    minMemberBalanceThreshold: '0',
    maxPerCtcAmount: null,
    thresholdCurrency: 'USD',
    updatedAt: null,
    updatedBy: null,
  });

  const emptyPage = () => ({
    content: [], total: 0, page: 0, size: 20, totalPages: 1,
  });

  function make(): CtcAutoComponent {
    const c = new CtcAutoComponent(ctcAutoConfig, tenantService, currencyService,
      permissionService, finance);
    c.ngOnInit();
    return c;
  }

  beforeEach(() => {
    ctcAutoConfig = jasmine.createSpyObj<CtcAutoConfigService>('CtcAutoConfigService', ['get', 'update']);
    ctcAutoConfig.get.and.returnValue(of(disabledConfig()));
    ctcAutoConfig.update.and.returnValue(of(disabledConfig()));

    tenantService = jasmine.createSpyObj<TenantService>('TenantService', ['getTenantId']);
    tenantService.getTenantId.and.returnValue('t1');

    currencyService = jasmine.createSpyObj<CurrencyService>('CurrencyService', ['listForTenant']);
    currencyService.listForTenant.and.returnValue(of([]));

    permissionService = jasmine.createSpyObj<PermissionService>('PermissionService', ['has']);
    permissionService.has.and.returnValue(true);

    finance = jasmine.createSpyObj<FinanceService>('FinanceService', ['listCtcPaymentsPaged']);
    finance.listCtcPaymentsPaged.and.returnValue(of(emptyPage()));
  });

  it('loads config, currencies, and recent auto-drafts on init', () => {
    make();
    expect(ctcAutoConfig.get).toHaveBeenCalledOnceWith('t1');
    expect(currencyService.listForTenant).toHaveBeenCalledOnceWith('t1');
    expect(finance.listCtcPaymentsPaged).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      systemDrafted: true,
      sortKey: 'createdAt',
      sortDirection: 'desc',
      page: 0,
      size: 20,
    }));
  });

  it('save uppercases currency and sends null cap when the field is empty', () => {
    const component = make();
    component.enabled = true;
    component.minMemberBalanceThreshold = '150.00';
    component.maxPerCtcAmount = '';
    component.thresholdCurrency = 'usd';

    component.save();

    expect(ctcAutoConfig.update).toHaveBeenCalledWith('t1', {
      enabled: true,
      minMemberBalanceThreshold: '150.00',
      maxPerCtcAmount: null,
      thresholdCurrency: 'USD',
    });
  });

  it('save forwards a non-null cap when the field is populated', () => {
    const component = make();
    component.enabled = true;
    component.minMemberBalanceThreshold = '100';
    component.maxPerCtcAmount = '50';
    component.thresholdCurrency = 'ZAR';

    component.save();

    expect(ctcAutoConfig.update).toHaveBeenCalledWith('t1', {
      enabled: true,
      minMemberBalanceThreshold: '100',
      maxPerCtcAmount: '50',
      thresholdCurrency: 'ZAR',
    });
  });

  it('save is blocked when the user lacks finance:configure_auto_ctc', () => {
    permissionService.has.and.returnValue(false);
    const component = make();
    component.enabled = true;
    component.save();

    expect(ctcAutoConfig.update).not.toHaveBeenCalled();
    expect(component.errorMessage).toContain('do not have permission');
  });

  it('hydrates the form from an existing config on load', () => {
    ctcAutoConfig.get.and.returnValue(of({
      tenantId: 't1', enabled: true,
      minMemberBalanceThreshold: '250.00',
      maxPerCtcAmount: '100.00',
      thresholdCurrency: 'ZAR',
      updatedAt: '2026-08-09T00:00:00Z', updatedBy: 'u1',
    }));
    const component = make();

    expect(component.enabled).toBeTrue();
    expect(component.minMemberBalanceThreshold).toBe('250.00');
    expect(component.maxPerCtcAmount).toBe('100.00');
    expect(component.thresholdCurrency).toBe('ZAR');
  });
});
