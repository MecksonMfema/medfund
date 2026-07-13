import { of } from 'rxjs';
import { PreAuthFormComponent } from './pre-auth-form.component';
import { PreAuthService } from '../../../../core/services/pre-auth.service';
import { ClaimsConfigService } from '../../../../core/services/claims-config.service';
import { ContributionsService, Scheme } from '../../../../core/services/contributions.service';
import { CurrencyService, TenantCurrencyConfig } from '../../../../core/services/currency.service';
import { Member, MembersService } from '../../../../core/services/members.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { EntityPickerSelection } from '../../../../shared/components/entity-picker/entity-picker.component';

/**
 * Component-level spec for {@link PreAuthFormComponent}. Drives the class
 * directly (no TestBed) — the interesting logic (recipient routing +
 * scheme auto-resolution + payload assembly with dependantId) lives on
 * the class, not in the template.
 *
 * <p>Coverage focus:
 * <ul>
 *   <li>Beneficiary picker resolves member vs dependant to
 *       {@code (memberId, dependantId)} correctly.</li>
 *   <li>Submit payload includes dependantId when the picker returned a
 *       dependant; null otherwise. Backend keys pre-auth lookup off
 *       dependantId when set, so the client contract matters.</li>
 *   <li>Client-side validation refuses to submit without a beneficiary,
 *       provider, scheme, or a positive requested amount.</li>
 * </ul>
 */
describe('PreAuthFormComponent', () => {
  let service: jasmine.SpyObj<PreAuthService>;
  let members: jasmine.SpyObj<MembersService>;
  let contributions: jasmine.SpyObj<ContributionsService>;
  let claimsConfig: jasmine.SpyObj<ClaimsConfigService>;
  let currency: jasmine.SpyObj<CurrencyService>;
  let tenant: TenantService;
  let router: jasmine.SpyObj<{ navigate: (...args: any[]) => any }>;
  let component: PreAuthFormComponent;

  const scheme = (): Scheme => ({
    id: 'scheme-1', name: 'Health Basic', currencyCode: 'USD',
    schemeType: 'medical_aid', insuranceLine: 'HEALTH',
    status: 'active', effectiveDate: '2026-01-01',
  } as Scheme);

  const memberPick = (): EntityPickerSelection => ({
    id: 'mem-1', label: 'Alice Ndlovu', sublabel: 'MEM · MBR-000001',
    beneficiary: { kind: 'MEMBER', memberId: 'mem-1', dependantId: null },
  });

  const dependantPick = (): EntityPickerSelection => ({
    id: 'dep-9', label: 'Tapiwa Ndlovu', sublabel: 'DEP · DEP-000901 — MBR-000001',
    beneficiary: {
      kind: 'DEPENDANT', memberId: 'mem-1', dependantId: 'dep-9',
      sponsorMemberNumber: 'MBR-000001', sponsorName: 'Alice Ndlovu',
    },
  });

  beforeEach(() => {
    service = jasmine.createSpyObj<PreAuthService>('PreAuthService', ['create']);
    members = jasmine.createSpyObj<MembersService>('MembersService', ['getById']);
    members.getById.and.callFake(id => of({ id, schemeId: 'scheme-1' } as Member));
    contributions = jasmine.createSpyObj<ContributionsService>('ContributionsService', ['getSchemeById']);
    contributions.getSchemeById.and.returnValue(of(scheme()));
    claimsConfig = jasmine.createSpyObj<ClaimsConfigService>('ClaimsConfigService', ['searchCodes']);
    claimsConfig.searchCodes.and.returnValue(of([]));
    currency = jasmine.createSpyObj<CurrencyService>('CurrencyService', ['listForTenant']);
    currency.listForTenant.and.returnValue(of<TenantCurrencyConfig[]>([]));
    router = jasmine.createSpyObj('Router', ['navigate']);

    tenant = { getTenant: () => ({ id: 't-1', insuranceLines: ['HEALTH'] }) } as unknown as TenantService;

    component = new PreAuthFormComponent(
      service, members, contributions, claimsConfig, currency, tenant as any, router as any,
    );
    component.ngOnInit();
  });

  it('member pick sets memberId with a null dependantId and resolves scheme', () => {
    component.onBeneficiaryPicked(memberPick());

    expect(component.memberId).toBe('mem-1');
    expect(component.dependantId).toBeNull();
    expect(component.beneficiaryKind).toBe('MEMBER');
    expect(component.schemeId).toBe('scheme-1');
    expect(component.schemeStatus).toBe('ok');
  });

  it('dependant pick routes sponsor into memberId and keeps dependantId', () => {
    component.onBeneficiaryPicked(dependantPick());

    expect(component.memberId).toBe('mem-1');
    expect(component.dependantId).toBe('dep-9');
    expect(component.beneficiaryKind).toBe('DEPENDANT');
    // The sponsor's scheme is used for a dependant claim.
    expect(component.schemeId).toBe('scheme-1');
  });

  it('submit for a dependant sends dependantId on the payload', () => {
    service.create.and.returnValue(of({
      id: 'pa-1', authNumber: 'PA-000001', memberId: 'mem-1', dependantId: 'dep-9',
      providerId: 'prov-1', tariffCode: 'TC001', status: 'pending',
      requestedAmount: '500.00', currencyCode: 'USD',
      requestedDate: '2026-07-13', createdAt: '2026-07-13T00:00:00Z',
    } as any));

    component.onBeneficiaryPicked(dependantPick());
    component.providerId = 'prov-1';
    component.form.tariffCode = 'TC001';
    component.form.requestedAmount = '500.00';

    component.submit();

    expect(service.create).toHaveBeenCalledOnceWith(jasmine.objectContaining({
      memberId: 'mem-1',
      dependantId: 'dep-9',
      providerId: 'prov-1',
      schemeId: 'scheme-1',
      tariffCode: 'TC001',
      requestedAmount: '500.00',
    }));
    expect(router.navigate).toHaveBeenCalledOnceWith(['/tenant/claims/preauth', 'pa-1']);
  });

  it('submit for a member pick sends dependantId=null', () => {
    service.create.and.returnValue(of({
      id: 'pa-2', authNumber: 'PA-000002', memberId: 'mem-1',
      providerId: 'prov-1', tariffCode: 'TC001', status: 'pending',
      requestedAmount: '250.00', currencyCode: 'USD',
      requestedDate: '2026-07-13', createdAt: '2026-07-13T00:00:00Z',
    } as any));

    component.onBeneficiaryPicked(memberPick());
    component.providerId = 'prov-1';
    component.form.tariffCode = 'TC001';
    component.form.requestedAmount = '250.00';

    component.submit();

    const arg = service.create.calls.mostRecent().args[0];
    expect(arg.memberId).toBe('mem-1');
    expect(arg.dependantId).toBeNull();
  });

  it('refuses to submit without a beneficiary', () => {
    component.providerId = 'prov-1';
    component.form.tariffCode = 'TC001';
    component.form.requestedAmount = '100.00';

    component.submit();

    expect(service.create).not.toHaveBeenCalled();
    expect(component.errorMessage).toContain('member or dependant');
  });

  it('refuses to submit without a provider', () => {
    component.onBeneficiaryPicked(memberPick());
    component.form.tariffCode = 'TC001';
    component.form.requestedAmount = '100.00';

    component.submit();

    expect(service.create).not.toHaveBeenCalled();
    expect(component.errorMessage).toContain('provider');
  });

  it('refuses to submit when requested amount is zero or missing', () => {
    component.onBeneficiaryPicked(memberPick());
    component.providerId = 'prov-1';
    component.form.tariffCode = 'TC001';
    component.form.requestedAmount = '0';

    component.submit();

    expect(service.create).not.toHaveBeenCalled();
    expect(component.errorMessage).toContain('greater than zero');
  });

  it('picking a tariff code fills description and defaults requested amount', () => {
    component.pickTariff({
      id: 't-1', scheduleId: 's-1', code: 'TC001', description: 'MRI Brain',
      unitPrice: '1200.00', currencyCode: 'USD',
    } as any);

    expect(component.form.tariffCode).toBe('TC001');
    expect(component.form.tariffDescription).toBe('MRI Brain');
    expect(component.form.requestedAmount).toBe('1200.00');
    expect(component.form.currencyCode).toBe('USD');
  });
});
