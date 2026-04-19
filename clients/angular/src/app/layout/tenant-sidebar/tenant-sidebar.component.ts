import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink, RouterLinkActive } from '@angular/router';
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

  navItems: NavItem[] = [
    { label: 'Dashboard',      icon: 'dashboard',   route: '/tenant/dashboard' },
    { label: 'Members',        icon: 'users',       route: '/tenant/members' },
    { label: 'Claims',         icon: 'file-text',   route: '/tenant/claims' },
    { label: 'Contributions',  icon: 'credit-card', route: '/tenant/contributions' },
    { label: 'Finance',        icon: 'banknote',    route: '/tenant/finance' },
    { label: 'Administration', icon: 'settings',    route: '/tenant/admin' },
  ];

  private sub?: Subscription;

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
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  toggleSidebar(): void {
    this.navService.toggleSidebar();
  }

  backToPlatform(): void {
    this.router.navigate(['/platform/tenants']);
  }

  async logout(): Promise<void> {
    await clearSession();
    this.keycloak.logout();
  }
}
