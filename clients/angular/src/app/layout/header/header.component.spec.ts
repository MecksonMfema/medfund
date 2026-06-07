import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { HeaderComponent } from './header.component';
import { NavigationService } from '../../core/services/navigation.service';
import { TenantService } from '../../core/services/tenant.service';
import { KeycloakService } from 'keycloak-angular';
import { MockKeycloakService } from '../../_test-utils/mock-keycloak.service';
import { MockTenantService, buildTenant } from '../../_test-utils/mock-tenant.service';
import { MockNavigationService } from '../../_test-utils/mock-navigation.service';
import { RouterHarness } from '../../_test-utils/router-harness';

function instantiate(opts: {
  url?: string,
  routeTitle?: string,
  roles?: string[],
  tenant?: ReturnType<typeof buildTenant> | null,
} = {}): {
  comp: HeaderComponent,
  events: Subject<unknown>,
  nav: MockNavigationService,
  tenant: MockTenantService,
  router: RouterHarness,
  keycloak: MockKeycloakService,
} {
  const events = new Subject<unknown>();
  const router = new RouterHarness();
  Object.assign(router, {
    url: opts.url ?? '/tenant/billing/schemes',
    events: events.asObservable(),
  });
  const route = {
    snapshot: { data: opts.routeTitle ? { title: opts.routeTitle } : {} },
    firstChild: null,
  } as unknown as ActivatedRoute;
  const nav = new MockNavigationService();
  const tenant = new MockTenantService(opts.tenant === null ? null : (opts.tenant ?? buildTenant()));
  const keycloak = new MockKeycloakService({ roles: opts.roles ?? ['operator'] });

  const comp = new HeaderComponent(
    router as unknown as Router,
    route,
    nav as unknown as NavigationService,
    tenant as unknown as TenantService,
    keycloak as unknown as KeycloakService,
  );
  return { comp, events, nav, tenant, router, keycloak };
}

describe('HeaderComponent', () => {
  it('derives the page title from the URL slug, with hyphens turned into spaces', () => {
    const { comp } = instantiate({ url: '/tenant/billing/age-groups' });
    comp.ngOnInit();
    expect(comp.pageTitle).toBe('Age groups');
    comp.ngOnDestroy();
  });

  it('falls back to "Dashboard" for an empty / root URL', () => {
    const { comp } = instantiate({ url: '/' });
    comp.ngOnInit();
    expect(comp.pageTitle).toBe('Dashboard');
    comp.ngOnDestroy();
  });

  it('prefers a route snapshot title over the URL-derived one', () => {
    const { comp } = instantiate({ url: '/anywhere', routeTitle: 'Custom Page' });
    comp.ngOnInit();
    expect(comp.pageTitle).toBe('Custom Page');
    comp.ngOnDestroy();
  });

  it('flags super admin from Keycloak realm roles', () => {
    const { comp } = instantiate({ roles: ['super_admin', 'operator'] });
    comp.ngOnInit();
    expect(comp.isSuperAdmin).toBe(true);
    comp.ngOnDestroy();
  });

  it('does not flag super admin when role is missing', () => {
    const { comp } = instantiate({ roles: ['operator'] });
    comp.ngOnInit();
    expect(comp.isSuperAdmin).toBe(false);
    comp.ngOnDestroy();
  });

  it('hasTenantContext is true only when a tenant is set', () => {
    const { comp, tenant } = instantiate();
    comp.ngOnInit();
    expect(comp.hasTenantContext).toBe(true);

    tenant.clearTenant();
    expect(comp.hasTenantContext).toBe(false);
    comp.ngOnDestroy();
  });

  it('updates pageTitle in response to NavigationEnd events', () => {
    const { comp, events, router } = instantiate({ url: '/tenant/dashboard' });
    comp.ngOnInit();
    expect(comp.pageTitle).toBe('Dashboard');

    (router as unknown as { url: string }).url ='/tenant/billing/age-groups';
    events.next(new NavigationEnd(1, '/tenant/billing/age-groups', '/tenant/billing/age-groups'));

    expect(comp.pageTitle).toBe('Age groups');
    comp.ngOnDestroy();
  });

  it('navigation helpers close the user menu and route correctly', () => {
    const { comp, router } = instantiate();
    comp.userMenuOpen = true;

    comp.goToOperations();
    expect(router.navigateCalls.pop()).toEqual(['/tenant/dashboard']);
    expect(comp.userMenuOpen).toBe(false);

    comp.userMenuOpen = true;
    comp.goToTenantAdmin();
    expect(router.navigateCalls.pop()).toEqual(['/tenant/admin/dashboard']);
    expect(comp.userMenuOpen).toBe(false);

    comp.userMenuOpen = true;
    comp.goToTenantPicker();
    expect(router.navigateCalls.pop()).toEqual(['/platform/tenants']);
    expect(comp.userMenuOpen).toBe(false);
  });

  it('toggleSidebar delegates to NavigationService', () => {
    const { comp, nav } = instantiate();
    comp.toggleSidebar();
    expect(nav.toggleCalls).toBe(1);
  });

  it('unsubscribes from router events on destroy', () => {
    const { comp, events, router } = instantiate({ url: '/x' });
    comp.ngOnInit();
    comp.ngOnDestroy();

    // After destroy, further nav events should not mutate pageTitle.
    const before = comp.pageTitle;
    (router as unknown as { url: string }).url ='/different-page';
    events.next(new NavigationEnd(2, '/different-page', '/different-page'));
    expect(comp.pageTitle).toBe(before);
  });
});
