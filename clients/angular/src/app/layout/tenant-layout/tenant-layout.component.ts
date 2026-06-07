import { Component, ElementRef, OnInit, OnDestroy, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { Subscription, filter, map } from 'rxjs';
import { KeycloakService } from 'keycloak-angular';
import { TenantSidebarComponent } from '../tenant-sidebar/tenant-sidebar.component';
import { OperationalSidebarComponent } from '../operational-sidebar/operational-sidebar.component';
import { NavigationService } from '../../core/services/navigation.service';
import { TenantService } from '../../core/services/tenant.service';
import { AdminService } from '../../core/services/admin.service';
import { BrandingService } from '../../core/services/branding.service';
import { PermissionService } from '../../core/security/permission.service';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { ProgressBarComponent } from '../../shared/components/progress-bar/progress-bar.component';
import { ToastContainerComponent } from '../../shared/components/toast/toast-container.component';
import { clearSession } from '../../auth/keycloak.init';

@Component({
  selector: 'app-tenant-layout',
  standalone: true,
  imports: [CommonModule, RouterOutlet, TenantSidebarComponent, OperationalSidebarComponent, IconComponent, ProgressBarComponent, ToastContainerComponent],
  templateUrl: './tenant-layout.component.html',
  styleUrl: './tenant-layout.component.scss',
})
export class TenantLayoutComponent implements OnInit, OnDestroy {
  @ViewChild('shell', { static: true }) shellRef!: ElementRef<HTMLDivElement>;

  collapsed = false;
  pageTitle = 'Dashboard';
  tenantName = '';
  tenantLogoUrl = '';
  tenantInitial = '';
  userName = 'User';
  userInitials = 'U';
  userMenuOpen = false;

  /** Realm-role flags — drive the portal-navigation items in the dropdown. */
  isSuperAdmin = false;
  isTenantAdmin = false;
  /**
   * Which sidebar to render — driven by route data {@code data.sidebar}:
   * <ul>
   *   <li>{@code 'operational'} → /tenant/{dashboard,claims,billing,finance,members}/*</li>
   *   <li>anything else (incl. unset) → IT-admin sidebar (the existing /tenant/admin/* tree)</li>
   * </ul>
   */
  sidebarVariant: 'admin' | 'operational' = 'admin';

  private subs: Subscription[] = [];

  constructor(
    private navService: NavigationService,
    private tenantService: TenantService,
    private adminService: AdminService,
    private brandingService: BrandingService,
    private keycloak: KeycloakService,
    private permissions: PermissionService,
    private router: Router,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.subs.push(this.navService.collapsed$.subscribe(c => (this.collapsed = c)));

    const roles = this.keycloak.getUserRoles(true) ?? [];
    this.isSuperAdmin  = roles.includes('super_admin');
    this.isTenantAdmin = roles.includes('tenant_admin');

    // If no tenant is in session (direct login or page refresh without navigation
    // from the platform admin), bootstrap it from the JWT tenant_id claim.
    //
    // Edge case: the platform realm hardcodes tenant_id="platform" for super_admin
    // users (see bootstrap-keycloak.sh). That's not a valid tenant UUID, so we
    // both skip the fetch AND redirect super_admin to the platform tenants list
    // — they can't usefully render the operational portal without a real tenant.
    if (!this.tenantService.getTenant()) {
      const token = this.keycloak.getKeycloakInstance()?.tokenParsed as Record<string, any> | undefined;
      const tenantId: string | undefined = token?.['tenant_id'];
      const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
      const isUuid = !!tenantId && UUID_RE.test(tenantId);

      if (isUuid) {
        this.adminService.getTenantById(tenantId!).subscribe({
          next: (full: any) => {
            // Lazy-import to avoid a static dep cycle with insurance-lines.
            import('../../core/models/insurance-lines').then(m => {
              const lines = m.parseInsuranceLines(full.settings);
              const term  = m.parseSchemeTerminology(full.settings);
              const drug  = m.parseDrugClaimsEnabled(full.settings);
              this.tenantService.setTenant({
                id: full.id,
                name: full.name,
                slug: full.slug,
                status: full.status,
                branding: full.branding ? this.brandingService.parseBranding(full.branding) : { templateId: 'platform' },
                timezone: full.timezone ?? '',
                insuranceLines: lines,
                providerRegLabel: m.parseProviderRegLabel(full.settings),
                schemeLabelSingular: term.singular,
                schemeLabelPlural:   term.plural,
                drugClaimsEnabled:   drug,
                membershipModel:     full.membershipModel,
              });
            });
          },
          error: () => { /* tenant fetch failed — proceed without branding */ },
        });
      } else {
        // No real tenant context available. If the user is super_admin send
        // them to the tenants picker; otherwise to /unauthorized.
        const roles = this.keycloak.getUserRoles(true) ?? [];
        const target = roles.includes('super_admin') ? '/platform/tenants' : '/unauthorized';
        this.router.navigate([target], { replaceUrl: true });
      }
    }

    // Apply branding + sync tenant identity whenever it changes
    this.subs.push(
      this.tenantService.tenant$.subscribe(t => {
        if (!t) return;
        this.tenantName    = t.name ?? '';
        this.tenantLogoUrl = t.branding?.logoUrl ?? '';
        this.tenantInitial = (t.name?.[0] ?? '').toUpperCase();
        if (t.branding) {
          this.brandingService.apply(this.shellRef.nativeElement, t.branding);
        }
      })
    );

    // Track page title + which sidebar variant the active route prefers.
    this.subs.push(
      this.router.events.pipe(
        filter(e => e instanceof NavigationEnd),
      ).subscribe(() => {
        this.pageTitle      = this.resolveTitle();
        this.sidebarVariant = this.resolveSidebar();
      })
    );
    this.pageTitle      = this.resolveTitle();
    this.sidebarVariant = this.resolveSidebar();

    // User info from Keycloak token
    const token = this.keycloak.getKeycloakInstance()?.idTokenParsed as Record<string, any> | undefined;
    const name = token?.['name'] || token?.['preferred_username'] || 'User';
    this.userName = name;
    this.userInitials = (name as string)
      .split(' ')
      .map((p: string) => p[0])
      .slice(0, 2)
      .join('')
      .toUpperCase();
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
    this.brandingService.reset(this.shellRef.nativeElement);
  }

  toggleSidebar(): void {
    this.navService.toggleSidebar();
  }

  /** Convenience for the template — true when at least one tenant-scoped
   *  admin permission is held (covers tenant admins who don't have the
   *  realm role but do have admin:* permissions). Super admins always pass. */
  get canAccessAdmin(): boolean {
    if (this.isSuperAdmin || this.isTenantAdmin) return true;
    return (
      this.permissions.has('admin:manage_settings') ||
      this.permissions.has('admin:manage_users') ||
      this.permissions.has('admin:manage_roles') ||
      this.permissions.has('admin:view_audit') ||
      this.permissions.has('admin:manage_rules')
    );
  }

  goToOperations(): void { this.router.navigate(['/tenant/dashboard']);       this.userMenuOpen = false; }
  goToTenantAdmin(): void { this.router.navigate(['/tenant/admin/dashboard']); this.userMenuOpen = false; }
  goToPlatform(): void    { this.router.navigate(['/platform/tenants']);      this.userMenuOpen = false; }

  async logout(): Promise<void> {
    await clearSession();
    this.keycloak.logout();
  }

  private resolveTitle(): string {
    let r = this.route;
    while (r.firstChild) r = r.firstChild;
    const t = r.snapshot.data['title'];
    if (t) return t;
    const seg = this.router.url.split('/').filter(Boolean).pop() || 'dashboard';
    return seg.charAt(0).toUpperCase() + seg.slice(1).replace(/-/g, ' ');
  }

  /**
   * Walks the activated route tree and returns the deepest {@code sidebar}
   * route-data value found. Defaults to {@code 'admin'} so existing
   * {@code /tenant/admin/*} routes (which don't declare {@code sidebar}) keep
   * the IT-admin sidebar.
   */
  private resolveSidebar(): 'admin' | 'operational' {
    let r = this.route;
    let pick: 'admin' | 'operational' = 'admin';
    while (r.firstChild) {
      r = r.firstChild;
      const v = r.snapshot.data['sidebar'];
      if (v === 'admin' || v === 'operational') pick = v;
    }
    return pick;
  }
}
