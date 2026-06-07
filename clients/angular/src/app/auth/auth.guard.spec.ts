import { TestBed } from '@angular/core/testing';
import { Router, ActivatedRouteSnapshot, RouterStateSnapshot, UrlTree } from '@angular/router';
import { KeycloakService } from 'keycloak-angular';
import { authGuard, roleGuard, rootRedirectGuard, permissionGuard } from './auth.guard';
import { PermissionService } from '../core/security/permission.service';
import { MockKeycloakService } from '../_test-utils/mock-keycloak.service';
import { MockPermissionService } from '../_test-utils/mock-permission.service';
import { RouterHarness } from '../_test-utils/router-harness';

function runGuard<T>(guard: (route: ActivatedRouteSnapshot, state: RouterStateSnapshot) => T): T {
  const route = {} as ActivatedRouteSnapshot;
  const state = { url: '/test' } as RouterStateSnapshot;
  return TestBed.runInInjectionContext(() => guard(route, state));
}

describe('authGuard', () => {
  let keycloak: MockKeycloakService;

  beforeEach(() => {
    keycloak = new MockKeycloakService({ loggedIn: true });
    TestBed.configureTestingModule({
      providers: [{ provide: KeycloakService, useValue: keycloak }],
    });
  });

  it('allows authenticated users through', async () => {
    const result = await runGuard(authGuard as never) as boolean;
    expect(result).toBe(true);
  });

  it('triggers Keycloak login and blocks navigation when unauthenticated', async () => {
    keycloak.setLoggedIn(false);
    const result = await runGuard(authGuard as never) as boolean;
    expect(result).toBe(false);
    expect(keycloak.loginCalls).toBe(1);
  });
});

describe('roleGuard', () => {
  let keycloak: MockKeycloakService;
  let router: RouterHarness;

  beforeEach(() => {
    keycloak = new MockKeycloakService({ loggedIn: true, roles: ['operator'] });
    router = new RouterHarness();
    TestBed.configureTestingModule({
      providers: [
        { provide: KeycloakService, useValue: keycloak },
        { provide: Router, useValue: router },
      ],
    });
  });

  it('allows when the user holds any of the required roles', async () => {
    const result = await runGuard(roleGuard(['operator', 'super_admin']) as never) as boolean;
    expect(result).toBe(true);
    expect(router.navigateCalls.length).toBe(0);
  });

  it('redirects to /unauthorized when no required role is held', async () => {
    keycloak.setRoles(['member']);
    const result = await runGuard(roleGuard(['operator']) as never) as boolean;
    expect(result).toBe(false);
    expect(router.navigateCalls).toEqual([['/unauthorized']]);
  });

  it('triggers Keycloak login when unauthenticated', async () => {
    keycloak.setLoggedIn(false);
    const result = await runGuard(roleGuard(['operator']) as never) as boolean;
    expect(result).toBe(false);
    expect(keycloak.loginCalls).toBe(1);
  });
});

describe('rootRedirectGuard', () => {
  let keycloak: MockKeycloakService;
  let router: RouterHarness;

  beforeEach(() => {
    keycloak = new MockKeycloakService({ loggedIn: true });
    router = new RouterHarness();
    TestBed.configureTestingModule({
      providers: [
        { provide: KeycloakService, useValue: keycloak },
        { provide: Router, useValue: router },
      ],
    });
  });

  it('routes super_admin to the platform dashboard', async () => {
    keycloak.setRoles(['super_admin']);
    await runGuard(rootRedirectGuard as never) as UrlTree | boolean;
    expect(router.createUrlTreeCalls).toEqual([['/platform/dashboard']]);
  });

  it('routes tenant-only users to the tenant operational dashboard', async () => {
    keycloak.setRoles(['operator']);
    await runGuard(rootRedirectGuard as never) as UrlTree | boolean;
    expect(router.createUrlTreeCalls).toEqual([['/tenant/dashboard']]);
  });

  it('routes role-less users to /unauthorized', async () => {
    keycloak.setRoles([]);
    await runGuard(rootRedirectGuard as never) as UrlTree | boolean;
    expect(router.createUrlTreeCalls).toEqual([['/unauthorized']]);
  });

  it('triggers Keycloak login when unauthenticated', async () => {
    keycloak.setLoggedIn(false);
    const result = await runGuard(rootRedirectGuard as never) as UrlTree | boolean;
    expect(result).toBe(false);
    expect(keycloak.loginCalls).toBe(1);
  });

  it('redirects to /unauthorized when getUserRoles throws', async () => {
    keycloak.getUserRoles = (() => { throw new Error('keycloak not ready'); }) as never;
    await runGuard(rootRedirectGuard as never) as UrlTree | boolean;
    expect(router.createUrlTreeCalls).toEqual([['/unauthorized']]);
  });
});

describe('permissionGuard', () => {
  let keycloak: MockKeycloakService;
  let router: RouterHarness;
  let permissions: MockPermissionService;

  beforeEach(() => {
    keycloak = new MockKeycloakService({ loggedIn: true, roles: ['operator'] });
    router = new RouterHarness();
    permissions = new MockPermissionService();
    TestBed.configureTestingModule({
      providers: [
        { provide: KeycloakService, useValue: keycloak },
        { provide: Router, useValue: router },
        { provide: PermissionService, useValue: permissions },
      ],
    });
  });

  it('allows immediately when the user already holds a required permission', async () => {
    permissions.emit(['claims:view']);
    const result = await runGuard(permissionGuard(['claims:view']) as never) as boolean;
    expect(result).toBe(true);
    expect(router.navigateCalls.length).toBe(0);
  });

  it('bypasses for super admins even with an empty permission set', async () => {
    keycloak.setRoles(['super_admin']);
    const result = await runGuard(permissionGuard(['claims:view']) as never) as boolean;
    expect(result).toBe(true);
    expect(router.navigateCalls.length).toBe(0);
  });

  it('waits for the permission set to populate then allows', async () => {
    const promise = runGuard(permissionGuard(['claims:view']) as never) as Promise<boolean>;
    queueMicrotask(() => permissions.emit(['claims:view']));
    const result = await promise;
    expect(result).toBe(true);
  });

  it('falls through to /unauthorized when permissions never arrive (2s timeout)', async () => {
    jasmine.clock().install();
    try {
      const promise = runGuard(permissionGuard(['claims:view']) as never) as Promise<boolean>;
      // Allow the guard to await isLoggedIn and reach the timeout pipe.
      await Promise.resolve();
      await Promise.resolve();
      jasmine.clock().tick(2001);
      const result = await promise;
      expect(result).toBe(false);
      expect(router.navigateCalls).toEqual([['/unauthorized']]);
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('triggers Keycloak login when unauthenticated', async () => {
    keycloak.setLoggedIn(false);
    const result = await runGuard(permissionGuard(['claims:view']) as never) as boolean;
    expect(result).toBe(false);
    expect(keycloak.loginCalls).toBe(1);
  });
});
