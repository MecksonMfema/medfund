import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
import { Subscription } from 'rxjs';
import { KeycloakService } from 'keycloak-angular';
import { NavigationService } from '../../core/services/navigation.service';
import { TenantService } from '../../core/services/tenant.service';
import { PermissionService } from '../../core/security/permission.service';
import { OPERATIONAL_NAV, OperationalNavGroup, OperationalNavItem } from './operational-nav';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { clearSession } from '../../auth/keycloak.init';
import { isPersonCentricLine } from '../../core/models/insurance-lines';
import { Tenant } from '../../core/services/tenant.service';

/**
 * Sidebar for the operational portal — claims, billing, finance, members.
 * Sibling of {@code TenantSidebarComponent} (which serves the IT-admin
 * console at {@code /tenant/admin/*}). One layout swaps between the two
 * via the route-data {@code sidebar} flag.
 *
 * <p>Items are permission-filtered: an item is visible only when the user
 * holds at least one of its declared permission keys. Empty groups (every
 * item filtered out) collapse — so a finance-only user never sees a Claims
 * section header at all.
 */
@Component({
  selector: 'app-operational-sidebar',
  standalone: true,
  imports: [CommonModule, RouterLink, RouterLinkActive, IconComponent],
  templateUrl: './operational-sidebar.component.html',
  styleUrl: './operational-sidebar.component.scss',
})
export class OperationalSidebarComponent implements OnInit, OnDestroy {
  collapsed = false;
  tenantName = '';
  logoUrl = '';
  tenantInitial = 'T';

  /** Visible groups + items — recomputed whenever the user's permission set changes. */
  visibleGroups: OperationalNavGroup[] = [];

  private subs: Subscription[] = [];

  constructor(
    private navService: NavigationService,
    private tenantService: TenantService,
    private permissions: PermissionService,
    private keycloak: KeycloakService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.subs.push(this.navService.collapsed$.subscribe(c => (this.collapsed = c)));

    this.subs.push(this.tenantService.tenant$.subscribe(tenant => {
      if (!tenant) return;
      this.tenantName    = tenant.name;
      this.tenantInitial = tenant.name?.[0]?.toUpperCase() ?? 'T';
      this.logoUrl       = tenant.branding?.logoUrl ?? '';
    }));

    this.subs.push(this.permissions.permissions$.subscribe(() => this.rebuildNav()));
    // Rebuild when the tenant's label settings change (e.g. admin saves new
    // scheme terminology) so the sidebar relabels without a page reload.
    this.subs.push(this.tenantService.tenant$.subscribe(() => this.rebuildNav()));
  }

  /**
   * Recompute visible groups from the canonical {@code OPERATIONAL_NAV}, the
   * caller's current permission set, and the tenant's chosen labels (e.g.
   * tenant calls them "Plans" instead of "Schemes").
   */
  private rebuildNav(): void {
    const set = this.permissions.snapshot();
    const tenant = this.tenantService.getTenant();
    const schemePlural = tenant?.schemeLabelPlural ?? 'Schemes';

    this.visibleGroups = OPERATIONAL_NAV
      .map(group => ({
        ...group,
        items: group.items
          .filter(item => this.allowed(item, set))
          .filter(item => this.featureFlagPasses(item, tenant))
          // Substitute the tenant's plural for "Schemes" wherever the label
          // appears verbatim. Keeps the canonical nav config tenant-agnostic.
          .map(item => item.label === 'Schemes' ? { ...item, label: schemePlural } : item),
      }))
      .filter(group => group.items.length > 0);
  }

  /**
   * Resolves the optional {@code featureFlag} on a nav item against the
   * tenant snapshot. Items without a flag pass unconditionally; unknown
   * flags fail closed so a typo hides the item until fixed.
   */
  private featureFlagPasses(item: OperationalNavItem, tenant: Tenant | null): boolean {
    if (!item.featureFlag) return true;
    switch (item.featureFlag) {
      case 'drugClaims':
        return tenant?.drugClaimsEnabled !== false;
      case 'ageGroupsAvailable':
        return (tenant?.insuranceLines ?? []).some(isPersonCentricLine);
      default:
        return false;
    }
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  toggleSidebar(): void {
    this.navService.toggleSidebar();
  }

  async logout(): Promise<void> {
    await clearSession();
    this.keycloak.logout();
  }

  /**
   * Visibility rule — the user needs ANY one of the declared permissions.
   * An item with an empty permission list is treated as universally visible
   * (used by the dashboard which everyone in the portal can see).
   *
   * <p>Delegates to {@link PermissionService#hasAny} so super admins (whose
   * tenant-scoped permission set is intentionally empty) see every nav item
   * without each call site having to repeat the role bypass.
   */
  private allowed(item: OperationalNavItem, _held: ReadonlySet<string>): boolean {
    if (!item.permissions || item.permissions.length === 0) return true;
    return this.permissions.hasAny(item.permissions);
  }

}
