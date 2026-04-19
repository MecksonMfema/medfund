import { Component, ElementRef, OnInit, OnDestroy, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { Subscription, filter, map } from 'rxjs';
import { KeycloakService } from 'keycloak-angular';
import { TenantSidebarComponent } from '../tenant-sidebar/tenant-sidebar.component';
import { NavigationService } from '../../core/services/navigation.service';
import { TenantService } from '../../core/services/tenant.service';
import { BrandingService } from '../../core/services/branding.service';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { clearSession } from '../../auth/keycloak.init';

@Component({
  selector: 'app-tenant-layout',
  standalone: true,
  imports: [CommonModule, RouterOutlet, TenantSidebarComponent, IconComponent],
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

  private subs: Subscription[] = [];

  constructor(
    private navService: NavigationService,
    private tenantService: TenantService,
    private brandingService: BrandingService,
    private keycloak: KeycloakService,
    private router: Router,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.subs.push(this.navService.collapsed$.subscribe(c => (this.collapsed = c)));

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

    // Track page title from route data
    this.subs.push(
      this.router.events.pipe(
        filter(e => e instanceof NavigationEnd),
        map(() => this.resolveTitle())
      ).subscribe(title => (this.pageTitle = title))
    );
    this.pageTitle = this.resolveTitle();

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
}
