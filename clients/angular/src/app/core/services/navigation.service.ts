import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { KeycloakService } from 'keycloak-angular';
import { NavGroup, UserInfo } from '../models/navigation.model';

const PLATFORM_ADMIN_NAV: NavGroup[] = [
  {
    items: [
      { label: 'Dashboard', route: '/platform/dashboard', icon: 'dashboard', roles: ['super_admin'] },
    ],
  },
  {
    title: 'MANAGEMENT',
    items: [
      { label: 'Tenants',   route: '/platform/tenants',   icon: 'building',   roles: ['super_admin'] },
      { label: 'Users',     route: '/platform/users',     icon: 'users',      roles: ['super_admin'] },
      { label: 'Providers', route: '/platform/providers', icon: 'building',   roles: ['super_admin'] },
      { label: 'Audit Logs',route: '/platform/audit',     icon: 'clipboard',  roles: ['super_admin'] },
    ],
  },
  {
    title: 'CONFIGURATION',
    items: [
      { label: 'Settings',  route: '/platform/settings',  icon: 'settings',   roles: ['super_admin'] },
      { label: 'Analytics', route: '/platform/analytics', icon: 'chart',      roles: ['super_admin'] },
    ],
  },
];

// Tenant IT-admin console: configures the tenant's slice of the platform.
// Operational portals (claims, finance, member self-service, providers) live
// in their own apps and are intentionally absent here. The hardcoded
// `tenant-sidebar.component.ts` array drives the actual rendering — this list
// is the canonical role-aware version that any other consumer of
// {@code NavigationService.getNavigation()} can rely on.
const TENANT_NAV: NavGroup[] = [
  {
    items: [
      { label: 'Dashboard',    route: '/tenant/admin/dashboard', icon: 'dashboard', roles: ['tenant_admin'] },
    ],
  },
  {
    title: 'MANAGEMENT',
    items: [
      { label: 'Users',        route: '/tenant/admin/users',     icon: 'users',     roles: ['tenant_admin'] },
      { label: 'Audit Logs',   route: '/tenant/admin/audit',     icon: 'clipboard', roles: ['tenant_admin'] },
      { label: 'Rules Engine', route: '/tenant/admin/rules',     icon: 'filter',    roles: ['tenant_admin'] },
      { label: 'Settings',     route: '/tenant/admin/settings',  icon: 'settings',  roles: ['tenant_admin'] },
    ],
  },
];

const ROLE_LABELS: Record<string, string> = {
  super_admin: 'Super Admin',
  tenant_admin: 'Tenant Admin',
  claims_clerk: 'Claims Clerk',
  finance_officer: 'Finance Officer',
  member: 'Member',
  provider: 'Provider',
};

@Injectable({ providedIn: 'root' })
export class NavigationService {
  private collapsedSubject = new BehaviorSubject<boolean>(false);
  collapsed$ = this.collapsedSubject.asObservable();

  constructor(private keycloak: KeycloakService) {}

  toggleSidebar(): void {
    this.collapsedSubject.next(!this.collapsedSubject.value);
  }

  setSidebarCollapsed(collapsed: boolean): void {
    this.collapsedSubject.next(collapsed);
  }

  get isCollapsed(): boolean {
    return this.collapsedSubject.value;
  }

  getNavigation(): NavGroup[] {
    const roles = this.getUserRoles();

    if (roles.includes('super_admin')) {
      return PLATFORM_ADMIN_NAV;
    }

    return TENANT_NAV
      .map((group) => ({
        ...group,
        items: group.items.filter((item) =>
          item.roles.some((role) => roles.includes(role))
        ),
      }))
      .filter((group) => group.items.length > 0);
  }

  getUserInfo(): UserInfo {
    try {
      const profile = this.keycloak.getKeycloakInstance().tokenParsed;
      const firstName = (profile as any)?.given_name || '';
      const lastName = (profile as any)?.family_name || '';
      const fullName = `${firstName} ${lastName}`.trim() || 'User';
      const email = (profile as any)?.email || '';
      const initials = fullName
        .split(' ')
        .map((n: string) => n[0])
        .join('')
        .toUpperCase()
        .slice(0, 2);
      const roles = this.getUserRoles();
      const roleLabel = roles
        .map((r) => ROLE_LABELS[r])
        .filter(Boolean)[0] || 'User';

      return { fullName, initials, email, roleLabel };
    } catch {
      return { fullName: 'User', initials: 'U', email: '', roleLabel: 'User' };
    }
  }

  isPlatformAdmin(): boolean {
    return this.getUserRoles().includes('super_admin');
  }

  private getUserRoles(): string[] {
    try {
      return this.keycloak.getUserRoles(true);
    } catch {
      return [];
    }
  }
}
