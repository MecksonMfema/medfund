import { Router } from '@angular/router';
import { OperationalSidebarComponent } from './operational-sidebar.component';
import { NavigationService } from '../../core/services/navigation.service';
import { TenantService } from '../../core/services/tenant.service';
import { PermissionService } from '../../core/security/permission.service';
import { KeycloakService } from 'keycloak-angular';
import { MockKeycloakService } from '../../_test-utils/mock-keycloak.service';
import { MockTenantService, buildTenant } from '../../_test-utils/mock-tenant.service';
import { MockPermissionService } from '../../_test-utils/mock-permission.service';
import { MockNavigationService } from '../../_test-utils/mock-navigation.service';
import { RouterHarness } from '../../_test-utils/router-harness';

function instantiate(opts: {
  initialPerms?: ReadonlyArray<string>,
  superAdmin?: boolean,
  schemeLabelPlural?: string,
} = {}) {
  const nav = new MockNavigationService();
  const tenant = new MockTenantService(buildTenant({
    name: 'Acme', schemeLabelPlural: opts.schemeLabelPlural,
  }));
  const permissions = new MockPermissionService(opts.initialPerms ?? [], { superAdmin: opts.superAdmin });
  const keycloak = new MockKeycloakService({ roles: opts.superAdmin ? ['super_admin'] : ['operator'] });
  const router = new RouterHarness();

  const comp = new OperationalSidebarComponent(
    nav as unknown as NavigationService,
    tenant as unknown as TenantService,
    permissions as unknown as PermissionService,
    keycloak as unknown as KeycloakService,
    router as unknown as Router,
  );
  return { comp, nav, tenant, permissions, keycloak, router };
}

describe('OperationalSidebarComponent', () => {
  it('reads collapsed state from the NavigationService stream', () => {
    const { comp, nav } = instantiate();
    comp.ngOnInit();
    expect(comp.collapsed).toBe(false);

    nav.setSidebarCollapsed(true);
    expect(comp.collapsed).toBe(true);
    comp.ngOnDestroy();
  });

  it('captures tenant branding on tenant emission', () => {
    const { comp, tenant } = instantiate();
    comp.ngOnInit();
    tenant.setTenant(buildTenant({
      name: 'Beta Health',
      branding: { logoUrl: 'https://logo.png' } as never,
    }));

    expect(comp.tenantName).toBe('Beta Health');
    expect(comp.tenantInitial).toBe('B');
    expect(comp.logoUrl).toBe('https://logo.png');
    comp.ngOnDestroy();
  });

  it('hides groups whose every item is permission-filtered out', () => {
    const { comp } = instantiate({ initialPerms: [] });
    comp.ngOnInit();

    // The Overview group has Dashboard which has no permissions so always
    // appears; Billing/Finance/etc. all need at least one permission and
    // should be filtered out.
    const visibleGroupTitles = comp.visibleGroups.map(g => g.title);
    expect(visibleGroupTitles).toContain('Overview');
    expect(visibleGroupTitles).not.toContain('Billing');
    comp.ngOnDestroy();
  });

  it('reveals a group when the user gains one of its item permissions', () => {
    const { comp, permissions } = instantiate({ initialPerms: [] });
    comp.ngOnInit();
    expect(comp.visibleGroups.find(g => g.title === 'Billing')).toBeUndefined();

    permissions.emit(['billing:view']);

    const billing = comp.visibleGroups.find(g => g.title === 'Billing');
    expect(billing).toBeDefined();
    expect(billing!.items.length).toBeGreaterThan(0);
    comp.ngOnDestroy();
  });

  it('shows every nav group for a super admin', () => {
    const { comp } = instantiate({ initialPerms: [], superAdmin: true });
    comp.ngOnInit();

    const titles = comp.visibleGroups.map(g => g.title);
    expect(titles).toContain('Overview');
    expect(titles).toContain('Billing');
    comp.ngOnDestroy();
  });

  it('substitutes the tenant Scheme plural label when present', () => {
    const { comp } = instantiate({
      initialPerms: ['billing:view'],
      schemeLabelPlural: 'Plans',
    });
    comp.ngOnInit();

    const schemesItem = comp.visibleGroups
      .flatMap(g => g.items)
      .find(item => item.icon === 'briefcase');
    expect(schemesItem?.label).toBe('Plans');
    comp.ngOnDestroy();
  });

  it('rebuilds nav when the tenant snapshot changes', () => {
    const { comp, tenant } = instantiate({ initialPerms: ['billing:view'] });
    comp.ngOnInit();
    const first = comp.visibleGroups
      .flatMap(g => g.items)
      .find(item => item.icon === 'briefcase');
    expect(first?.label).toBe('Schemes');

    tenant.setTenant(buildTenant({ name: 'X', schemeLabelPlural: 'Policies' }));
    const after = comp.visibleGroups
      .flatMap(g => g.items)
      .find(item => item.icon === 'briefcase');
    expect(after?.label).toBe('Policies');
    comp.ngOnDestroy();
  });

  it('toggleSidebar delegates to the NavigationService', () => {
    const { comp, nav } = instantiate();
    comp.toggleSidebar();
    expect(nav.toggleCalls).toBe(1);
  });

  it('unsubscribes from all observables on destroy', () => {
    const { comp, permissions, tenant } = instantiate({ initialPerms: ['billing:view'] });
    comp.ngOnInit();
    comp.ngOnDestroy();
    // After destroy, emissions should be no-ops — no errors thrown.
    expect(() => {
      permissions.emit([]);
      tenant.setTenant(buildTenant({ name: 'Late' }));
    }).not.toThrow();
  });
});
