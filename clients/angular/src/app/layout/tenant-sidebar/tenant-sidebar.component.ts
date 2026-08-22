import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { Subscription } from 'rxjs';
import { KeycloakService } from 'keycloak-angular';
import { NavigationService } from '../../core/services/navigation.service';
import { TenantService } from '../../core/services/tenant.service';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { clearSession } from '../../auth/keycloak.init';

interface NavItem {
  label: string;
  icon: string;
  route: string;
}

@Component({
  selector: 'app-tenant-sidebar',
  standalone: true,
  imports: [CommonModule, RouterLink, RouterLinkActive, IconComponent],
  templateUrl: './tenant-sidebar.component.html',
  styleUrl: './tenant-sidebar.component.scss',
})
export class TenantSidebarComponent implements OnInit, OnDestroy {
  collapsed = false;
  tenantName = '';
  tenantSlug = '';
  logoUrl = '';
  tenantInitial = 'T';


  // Tenant IT-admin console: configures the tenant's own slice of the platform.
  // Operational portals (claims adjudication, finance, member self-service,
  // provider workflows) live in their own apps; intentionally not linked here.
  navItems: NavItem[] = [
    { label: 'Dashboard',    icon: 'dashboard', route: '/tenant/admin/dashboard' },
    { label: 'Users',        icon: 'users',     route: '/tenant/admin/users' },
    { label: 'Audit Logs',   icon: 'clipboard', route: '/tenant/admin/audit' },
    { label: 'Rules Engine', icon: 'filter',    route: '/tenant/admin/rules' },
    { label: 'Reinsurance',  icon: 'shield',    route: '/tenant/admin/reinsurance' },
    { label: 'Settings',     icon: 'settings',  route: '/tenant/admin/settings' },
  ];

  private sub?: Subscription;

  constructor(
    private navService: NavigationService,
    private tenantService: TenantService,
    private keycloak: KeycloakService,
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

  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  toggleSidebar(): void {
    this.navService.toggleSidebar();
  }

  async logout(): Promise<void> {
    await clearSession();
    this.keycloak.logout();
  }
}
