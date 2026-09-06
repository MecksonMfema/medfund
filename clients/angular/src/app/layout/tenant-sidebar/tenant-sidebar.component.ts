import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { Subscription, filter } from 'rxjs';
import { KeycloakService } from 'keycloak-angular';
import { NavigationService } from '../../core/services/navigation.service';
import { TenantService } from '../../core/services/tenant.service';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { clearSession } from '../../auth/keycloak.init';

interface NavItem {
  label: string;
  icon: string;
  route: string;
  /**
   * Optional URL prefix used to compute the active state. Defaults to
   * {@code route}. Provide a broader prefix when the sidebar entry should
   * stay highlighted on any sub-route.
   */
  matchPrefix?: string;
  /**
   * Sub-paths that must NOT be treated as active for this item, even
   * though they'd otherwise match {@code matchPrefix}. Used so a parent
   * entry (e.g. Producers) can stay lit on its own sub-pages without
   * co-highlighting with a sibling entry (e.g. Treaty backfill) whose
   * route sits under the same prefix.
   */
  excludePrefixes?: string[];
}

@Component({
  selector: 'app-tenant-sidebar',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent],
  templateUrl: './tenant-sidebar.component.html',
  styleUrl: './tenant-sidebar.component.scss',
})
export class TenantSidebarComponent implements OnInit, OnDestroy {
  collapsed = false;
  tenantName = '';
  tenantSlug = '';
  logoUrl = '';
  tenantInitial = 'T';

  currentUrl = '';

  // Tenant IT-admin console: configures the tenant's own slice of the platform.
  // Operational portals (claims adjudication, finance, member self-service,
  // provider workflows) live in their own apps; intentionally not linked here.
  navItems: NavItem[] = [
    { label: 'Dashboard',    icon: 'dashboard', route: '/tenant/admin/dashboard' },
    { label: 'Users',        icon: 'users',     route: '/tenant/admin/users' },
    { label: 'Audit Logs',   icon: 'clipboard', route: '/tenant/admin/audit' },
    { label: 'Rules Engine', icon: 'filter',    route: '/tenant/admin/rules' },
    { label: 'Reinsurance',  icon: 'shield',    route: '/tenant/admin/reinsurance' },
    // Producers is the parent nav for /tenant/admin/producers/*, but
    // Treaty backfill sits under the same prefix and gets its own entry.
    // matchPrefix stays broad; excludePrefixes carves out the backfill
    // path so both sidebar rows don't light up simultaneously.
    {
      label: 'Producers',
      icon: 'briefcase',
      route: '/tenant/admin/producers',
      matchPrefix: '/tenant/admin/producers',
      excludePrefixes: ['/tenant/admin/producers/backfill'],
    },
    { label: 'Treaty backfill', icon: 'history', route: '/tenant/admin/producers/backfill' },
    { label: 'Underwriting', icon: 'layers',    route: '/tenant/admin/underwriting' },
    { label: 'Settings',     icon: 'settings',  route: '/tenant/admin/settings' },
  ];

  private sub?: Subscription;
  private routerSub?: Subscription;

  constructor(
    private navService: NavigationService,
    private tenantService: TenantService,
    private keycloak: KeycloakService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.sub = this.navService.collapsed$.subscribe(c => (this.collapsed = c));

    this.tenantService.tenant$.subscribe(tenant => {
      if (!tenant) return;
      this.tenantName   = tenant.name;
      this.tenantSlug   = tenant.slug;
      this.tenantInitial = tenant.name?.[0]?.toUpperCase() ?? 'T';
      this.logoUrl       = tenant.branding?.logoUrl ?? '';
    });

    this.currentUrl = this.router.url;
    this.routerSub = this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(e => { this.currentUrl = e.urlAfterRedirects; });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
    this.routerSub?.unsubscribe();
  }

  toggleSidebar(): void {
    this.navService.toggleSidebar();
  }

  /**
   * Resolves the active state for a nav item using {@link NavItem.matchPrefix}
   * and {@link NavItem.excludePrefixes} if provided; otherwise falls back to
   * a plain prefix match on {@link NavItem.route}. Split from the template so
   * excluded prefixes take precedence over the parent match.
   */
  isActive(item: NavItem): boolean {
    const url = this.currentUrl.split('?')[0].split('#')[0];
    if (item.excludePrefixes?.some(p => url === p || url.startsWith(p + '/'))) {
      return false;
    }
    const prefix = item.matchPrefix ?? item.route;
    return url === prefix || url.startsWith(prefix + '/');
  }

  async logout(): Promise<void> {
    await clearSession();
    this.keycloak.logout();
  }
}
