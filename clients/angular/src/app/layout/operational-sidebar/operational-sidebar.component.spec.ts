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
  insuranceLines?: string[],
  membershipModel?: 'INDIVIDUAL_ONLY' | 'GROUP_ONLY' | 'BOTH',
} = {}) {
  const nav = new MockNavigationService();
  const tenant = new MockTenantService(buildTenant({
    name: 'Acme',
    schemeLabelPlural: opts.schemeLabelPlural,
    ...(opts.insuranceLines !== undefined ? { insuranceLines: opts.insuranceLines } : {}),
    ...(opts.membershipModel !== undefined ? { membershipModel: opts.membershipModel } : {}),
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

  it('hides Age Groups when the tenant has only asset-centric insurance lines', () => {
    const { comp } = instantiate({
      initialPerms: ['billing:view', 'billing:manage_age_groups'],
      insuranceLines: ['VEHICLE'],
    });
    comp.ngOnInit();

    const items = comp.visibleGroups.flatMap(g => g.items);
    expect(items.some(i => i.label === 'Age Groups')).toBe(false);
    // Other Billing items still surface — gating is per-item, not group-wide.
    expect(items.some(i => i.icon === 'briefcase')).toBe(true);
    comp.ngOnDestroy();
  });

  it('shows Age Groups when the tenant has at least one person-centric line', () => {
    const { comp } = instantiate({
      initialPerms: ['billing:view', 'billing:manage_age_groups'],
      insuranceLines: ['VEHICLE', 'HEALTH'],
    });
    comp.ngOnInit();

    const items = comp.visibleGroups.flatMap(g => g.items);
    expect(items.some(i => i.label === 'Age Groups')).toBe(true);
    comp.ngOnDestroy();
  });

  it('declares Members with exactMatch so the parent stops highlighting on child routes', () => {
    // Sidebar template binds routerLinkActiveOptions to { exact: !!item.exactMatch }.
    // This guards against a regression of the sidebar template losing that binding —
    // we assert the canonical OPERATIONAL_NAV still flags the Members entry.
    const { comp } = instantiate({ initialPerms: ['members:view'] });
    comp.ngOnInit();
    const membersItem = comp.visibleGroups
      .flatMap(g => g.items)
      .find(i => i.route === '/tenant/members');
    expect(membersItem?.exactMatch).toBe(true);
    comp.ngOnDestroy();
  });

  it('hides Groups for an INDIVIDUAL_ONLY tenant', () => {
    const { comp } = instantiate({
      initialPerms: ['members:view', 'billing:manage_groups'],
      membershipModel: 'INDIVIDUAL_ONLY',
    });
    comp.ngOnInit();

    const items = comp.visibleGroups.flatMap(g => g.items);
    expect(items.some(i => i.label === 'Groups')).toBe(false);
    // Members itself still renders.
    expect(items.some(i => i.label === 'Members')).toBe(true);
    comp.ngOnDestroy();
  });

  it('shows Groups for a GROUP_ONLY tenant', () => {
    const { comp } = instantiate({
      initialPerms: ['members:view', 'billing:manage_groups'],
      membershipModel: 'GROUP_ONLY',
    });
    comp.ngOnInit();

    const items = comp.visibleGroups.flatMap(g => g.items);
    expect(items.some(i => i.label === 'Groups')).toBe(true);
    comp.ngOnDestroy();
  });

  it('shows Groups when membershipModel is missing (back-compat default = BOTH)', () => {
    const { comp } = instantiate({
      initialPerms: ['members:view', 'billing:manage_groups'],
    });
    comp.ngOnInit();

    const items = comp.visibleGroups.flatMap(g => g.items);
    expect(items.some(i => i.label === 'Groups')).toBe(true);
    comp.ngOnDestroy();
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
