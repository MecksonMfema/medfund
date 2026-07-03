import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { Observable, Subscription, filter, map } from 'rxjs';
import { KeycloakService } from 'keycloak-angular';
import { NavigationService } from '../../core/services/navigation.service';
import { TenantService } from '../../core/services/tenant.service';
import { NotificationsService, UserNotification } from '../../core/services/notifications.service';
import { UserInfo } from '../../core/models/navigation.model';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { clearSession, endImpersonation } from '../../auth/keycloak.init';

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule, IconComponent],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss',
})
export class HeaderComponent implements OnInit, OnDestroy {
  pageTitle = 'Dashboard';
  userInfo: UserInfo = { fullName: 'User', initials: 'U', email: '', roleLabel: 'User' };
  userMenuOpen = false;
  notificationsOpen = false;

  /** Realm-role flags — drive portal-navigation items in the dropdown. */
  isSuperAdmin = false;

  /** Notifications observables piped straight into the bell dropdown via async pipe. */
  unseenCount$!: Observable<number>;
  notifications$!: Observable<UserNotification[]>;

  private sub?: Subscription;

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    private navService: NavigationService,
    private tenantService: TenantService,
    private keycloak: KeycloakService,
    private notifications: NotificationsService,
  ) {}

  /** True when the super admin has already picked a tenant — controls
   *  whether the dropdown shows the Tenant Admin / Operational Portal hops
   *  (those need a tenant context). */
  get hasTenantContext(): boolean {
    return !!this.tenantService.getTenant();
  }

  goToOperations(): void { this.router.navigate(['/tenant/dashboard']);       this.userMenuOpen = false; }
  goToTenantAdmin(): void { this.router.navigate(['/tenant/admin/dashboard']); this.userMenuOpen = false; }
  goToTenantPicker(): void {
    this.endActiveImpersonation();
    this.router.navigate(['/platform/tenants']);
    this.userMenuOpen = false;
  }

  async logout(): Promise<void> {
    this.endActiveImpersonation();
    await clearSession();
    this.keycloak.logout();
  }

  /** Fire IMPERSONATION_END if the super admin currently has a tenant in
   *  context. Fire-and-forget — failures are swallowed inside the helper. */
  private endActiveImpersonation(): void {
    const t = this.tenantService.getTenant();
    if (t?.id) {
      endImpersonation(t.id, t.name);
    }
  }

  // ── Notifications bell ──────────────────────────────────────────────

  toggleNotifications(): void {
    this.notificationsOpen = !this.notificationsOpen;
    if (this.notificationsOpen) {
      // Refresh on open so the dropdown's always current even if the
      // user opens it 29s into the poll cycle.
      this.notifications.refresh();
    }
  }

  markAllSeen(): void {
    this.notifications.markAllSeen();
  }

  onRowClick(n: UserNotification): void {
    if (!n.seen) this.notifications.markSeen(n.id);
    if (n.actionUrl) this.router.navigateByUrl(n.actionUrl);
  }

  /** Bell dot colour, driven by the persisted severity column. Producers
   *  choose which severity fits their event; unknown values render gray. */
  severityColor(s: string): string {
    return { info: 'blue', success: 'green', warning: 'amber', error: 'red' }[s] ?? 'gray';
  }

  /** Compact relative time — "5m ago", "2h ago", "yesterday". Server
   *  sends UTC ISO timestamps; the browser handles the timezone. */
  relative(iso: string): string {
    const then = new Date(iso).getTime();
    if (!Number.isFinite(then)) return '';
    const secs = Math.floor((Date.now() - then) / 1000);
    if (secs < 60)   return `${Math.max(1, secs)}s ago`;
    if (secs < 3600) return `${Math.floor(secs / 60)}m ago`;
    if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`;
    if (secs < 172800) return 'yesterday';
    return `${Math.floor(secs / 86400)}d ago`;
  }

  ngOnInit(): void {
    this.userInfo = this.navService.getUserInfo();
    this.isSuperAdmin = (this.keycloak.getUserRoles(true) ?? []).includes('super_admin');

    // Notifications: start the shared polling loop + bind the streams
    // the template binds via async pipe.
    this.notifications.start();
    this.unseenCount$ = this.notifications.badge;
    this.notifications$ = this.notifications.list;

    this.sub = this.router.events
      .pipe(
        filter((e) => e instanceof NavigationEnd),
        map(() => {
          let r = this.route;
          while (r.firstChild) r = r.firstChild;
          return r.snapshot.data['title'] || this.deriveTitle(this.router.url);
        })
      )
      .subscribe((title) => (this.pageTitle = title));

    // Set initial title
    let r = this.route;
    while (r.firstChild) r = r.firstChild;
    this.pageTitle = r.snapshot.data['title'] || this.deriveTitle(this.router.url);
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  toggleSidebar(): void {
    this.navService.toggleSidebar();
  }

  private deriveTitle(url: string): string {
    const segment = url.split('/').filter(Boolean).pop() || 'dashboard';
    return segment.charAt(0).toUpperCase() + segment.slice(1).replace(/-/g, ' ');
  }
}
