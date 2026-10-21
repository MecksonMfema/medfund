import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ProvidersComponent } from './providers.component';
import {
  Provider,
  ProviderPage,
  ProviderTenant,
  ProvidersService,
} from '../../core/services/providers.service';
import { AdminService, Tenant, TenantPage } from '../../core/services/admin.service';
import { ToastService } from '../../shared/components/toast/toast.service';

/**
 * Covers the Tenants & lines editor. A provider row in public.providers is
 * inert until it has a membership row and a line tag, so what matters here is
 * that each toggle issues the right call, that the chip reflects server state
 * rather than optimistic local state, and that a rejected call leaves the chip
 * where it was instead of lying about the junction.
 */
describe('ProvidersComponent - tenants & lines', () => {
  let fixture: ComponentFixture<ProvidersComponent>;
  let component: ProvidersComponent;
  let providers: jasmine.SpyObj<ProvidersService>;
  let admin: jasmine.SpyObj<AdminService>;
  let toast: jasmine.SpyObj<ToastService>;

  const HEALTH_FIRST = 'aaaaaaaa-0000-4000-8000-000000000001';
  const LIFE_FIRST = 'bbbbbbbb-0000-4000-8000-000000000002';

  const tenants: Tenant[] = [
    {
      id: HEALTH_FIRST, name: 'Health First Medical', slug: 'health-first',
      status: 'active', contactEmail: 'a@b.c', countryCode: 'ZW', membershipModel: 'BOTH',
    } as Tenant,
    {
      id: LIFE_FIRST, name: 'Life First Assurance', slug: 'life-first',
      status: 'active', contactEmail: 'a@b.c', countryCode: 'ZA', membershipModel: 'BOTH',
    } as Tenant,
  ];

  const provider: Provider = {
    id: 'ccccccc1-0000-4000-8000-000000000003',
    name: 'Sunrise Clinic',
    providerType: 'HEALTHCARE',
    specialty: 'GP',
    registrationNumber: 'REG-1',
    email: 'clinic@example.com',
    phone: '+263',
    city: 'Harare',
    address: '1 Main',
    status: 'active',
    tenantIds: [HEALTH_FIRST],
    insuranceLines: ['HEALTH'],
    createdAt: '2026-09-01T00:00:00Z',
  };

  const page: ProviderPage = {
    content: [provider], totalCount: 1, totalPages: 1, page: 1, size: 20,
  };

  const membership: ProviderTenant = {
    providerId: provider.id, tenantId: HEALTH_FIRST, status: 'active',
    networkTier: 'STANDARD', inNetwork: true,
  };

  beforeEach(async () => {
    providers = jasmine.createSpyObj<ProvidersService>('ProvidersService', [
      'query', 'verify', 'suspend', 'activate', 'updateNetworkTier', 'onboard',
      'listMemberships', 'link', 'unlink', 'listLines', 'addLine', 'removeLine',
    ]);
    admin = jasmine.createSpyObj<AdminService>('AdminService', ['getTenants']);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);

    providers.query.and.returnValue(of(page));
    providers.listMemberships.and.returnValue(of([membership]));
    providers.listLines.and.returnValue(of(['HEALTH']));
    admin.getTenants.and.returnValue(of({
      content: tenants, totalCount: 2, totalPages: 1, page: 1, size: 100,
    } as TenantPage));

    await TestBed.configureTestingModule({
      imports: [ProvidersComponent],
      providers: [
        { provide: ProvidersService, useValue: providers },
        { provide: AdminService, useValue: admin },
        { provide: ToastService, useValue: toast },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProvidersComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('resolves tenant UUIDs to names for the Tenants pill column', () => {
    // feedback_no_raw_id_inputs: the payload holds the UUID, the UI shows the name.
    expect(component.tenantName(HEALTH_FIRST)).toBe('Health First Medical');
  });

  it('loads both junctions fresh when the modal opens', () => {
    component.openMembershipModal(provider);

    expect(providers.listMemberships).toHaveBeenCalledWith(provider.id);
    expect(providers.listLines).toHaveBeenCalledWith(provider.id);
    expect(component.isTenantLinked(HEALTH_FIRST)).toBeTrue();
    expect(component.isTenantLinked(LIFE_FIRST)).toBeFalse();
    expect(component.isLineTagged('HEALTH')).toBeTrue();
    expect(component.isLineTagged('LIFE')).toBeFalse();
  });

  it('links an unlinked tenant and turns its chip on', () => {
    providers.link.and.returnValue(of({ ...membership, tenantId: LIFE_FIRST }));
    component.openMembershipModal(provider);

    component.toggleTenant(LIFE_FIRST);

    expect(providers.link).toHaveBeenCalledWith(provider.id, LIFE_FIRST);
    expect(providers.unlink).not.toHaveBeenCalled();
    expect(component.isTenantLinked(LIFE_FIRST)).toBeTrue();
  });

  it('unlinks an already-linked tenant', () => {
    providers.unlink.and.returnValue(of(void 0));
    component.openMembershipModal(provider);

    component.toggleTenant(HEALTH_FIRST);

    expect(providers.unlink).toHaveBeenCalledWith(provider.id, HEALTH_FIRST);
    expect(component.isTenantLinked(HEALTH_FIRST)).toBeFalse();
  });

  it('leaves the chip untouched and surfaces the error when linking fails', () => {
    providers.link.and.returnValue(
      throwError(() => ({ error: { detail: 'Provider is already linked' } })));
    component.openMembershipModal(provider);

    component.toggleTenant(LIFE_FIRST);

    expect(component.isTenantLinked(LIFE_FIRST))
      .withContext('a failed link must not leave a chip claiming the row exists')
      .toBeFalse();
    expect(toast.error).toHaveBeenCalledWith('Provider is already linked');
    expect(component.isBusy('t:' + LIFE_FIRST)).toBeFalse();
  });

  it('adds an untagged insurance line', () => {
    providers.addLine.and.returnValue(of(void 0));
    component.openMembershipModal(provider);

    component.toggleLine('TRAVEL');

    expect(providers.addLine).toHaveBeenCalledWith(provider.id, 'TRAVEL');
    expect(component.isLineTagged('TRAVEL')).toBeTrue();
  });

  it('removes a tagged insurance line', () => {
    providers.removeLine.and.returnValue(of(void 0));
    component.openMembershipModal(provider);

    component.toggleLine('HEALTH');

    expect(providers.removeLine).toHaveBeenCalledWith(provider.id, 'HEALTH');
    expect(component.isLineTagged('HEALTH')).toBeFalse();
  });

  it('reloads the list on close so the pills match the junctions', () => {
    component.openMembershipModal(provider);
    providers.query.calls.reset();

    component.closeMembershipModal();

    expect(component.showMembershipModal).toBeFalse();
    expect(providers.query).toHaveBeenCalled();
  });
});
