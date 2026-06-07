import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { KeycloakService } from 'keycloak-angular';
import { PermissionService } from './permission.service';
import { ApiService } from '../services/api.service';
import { TenantService } from '../services/tenant.service';
import { MockTenantService, buildTenant } from '../../_test-utils/mock-tenant.service';
import { MockKeycloakService } from '../../_test-utils/mock-keycloak.service';

class MockApiService {
  responses: Array<Observable<string[]>> = [];
  calls = 0;

  enqueue(observable: Observable<string[]>): void {
    this.responses.push(observable);
  }

  get<T>(_path: string): Observable<T> {
    this.calls += 1;
    const next = this.responses.shift() ?? of([] as string[]);
    return next as unknown as Observable<T>;
  }
}

describe('PermissionService', () => {
  let api: MockApiService;
  let tenant: MockTenantService;
  let keycloak: MockKeycloakService;

  function setup(): PermissionService {
    api = new MockApiService();
    tenant = new MockTenantService(null);
    keycloak = new MockKeycloakService({ loggedIn: true, roles: [] });

    TestBed.configureTestingModule({
      providers: [
        PermissionService,
        { provide: ApiService, useValue: api },
        { provide: TenantService, useValue: tenant },
        { provide: KeycloakService, useValue: keycloak },
      ],
    });
    return TestBed.inject(PermissionService);
  }

  it('starts with an empty permission set and no fetch when no tenant is active', () => {
    const svc = setup();
    expect(svc.snapshot().size).toBe(0);
    expect(api.calls).toBe(0);
  });

  it('fetches /me/permissions once when a tenant becomes active', () => {
    const svc = setup();
    api.enqueue(of(['claims:view', 'billing:view']));

    tenant.setTenant(buildTenant({ id: 'tenant-a' }));

    expect(api.calls).toBe(1);
    expect(Array.from(svc.snapshot()).sort()).toEqual(['billing:view', 'claims:view']);
  });

  it('refetches only when the tenant id changes (distinctUntilChanged)', () => {
    const svc = setup();
    api.enqueue(of(['claims:view']));
    api.enqueue(of(['claims:view', 'finance:view']));

    tenant.setTenant(buildTenant({ id: 'tenant-a' }));
    tenant.setTenant(buildTenant({ id: 'tenant-a', name: 'Renamed' })); // same id
    tenant.setTenant(buildTenant({ id: 'tenant-b' }));                  // new id

    expect(api.calls).toBe(2);
    expect(svc.snapshot().has('finance:view')).toBe(true);
  });

  it('treats /me/permissions failure as no-permissions instead of crashing', () => {
    const svc = setup();
    api.enqueue(throwError(() => new Error('boom')));

    tenant.setTenant(buildTenant({ id: 'tenant-a' }));

    expect(svc.snapshot().size).toBe(0);
    expect(svc.has('claims:view')).toBe(false);
  });

  it('has() returns true for super admin regardless of fetched permissions', () => {
    const svc = setup();
    keycloak.setRoles(['super_admin']);
    expect(svc.isSuperAdmin()).toBe(true);
    expect(svc.has('anything:you:like')).toBe(true);
  });

  it('hasAny() short-circuits true for super admin even on empty input', () => {
    const svc = setup();
    keycloak.setRoles(['super_admin']);
    expect(svc.hasAny([])).toBe(true);
  });

  it('hasAny() returns true when any one permission is held', () => {
    const svc = setup();
    api.enqueue(of(['claims:view']));
    tenant.setTenant(buildTenant({ id: 'tenant-a' }));

    expect(svc.hasAny(['finance:view', 'claims:view'])).toBe(true);
    expect(svc.hasAny(['finance:view', 'billing:view'])).toBe(false);
  });

  it('emits the latest set to late subscribers via BehaviorSubject replay', (done) => {
    const svc = setup();
    api.enqueue(of(['claims:view']));
    tenant.setTenant(buildTenant({ id: 'tenant-a' }));

    svc.permissions$.subscribe(set => {
      expect(set.has('claims:view')).toBe(true);
      done();
    });
  });

  it('refresh() re-fetches and pushes the new set without changing the tenant', () => {
    const svc = setup();
    api.enqueue(of(['claims:view']));
    tenant.setTenant(buildTenant({ id: 'tenant-a' }));
    expect(svc.snapshot().has('claims:view')).toBe(true);

    api.enqueue(of(['claims:view', 'claims:adjudicate']));
    let next: ReadonlySet<string> | undefined;
    svc.refresh().subscribe(s => (next = s));

    expect(api.calls).toBe(2);
    expect(next?.has('claims:adjudicate')).toBe(true);
    expect(svc.snapshot().has('claims:adjudicate')).toBe(true);
  });

  it('clears permissions when the tenant is cleared (platform-only user)', () => {
    const svc = setup();
    api.enqueue(of(['claims:view']));
    tenant.setTenant(buildTenant({ id: 'tenant-a' }));
    expect(svc.snapshot().size).toBe(1);

    tenant.clearTenant();
    expect(svc.snapshot().size).toBe(0);
  });
});
