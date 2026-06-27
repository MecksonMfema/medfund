import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { Observable, Subscription, filter, map } from 'rxjs';
import { KeycloakService } from 'keycloak-angular';
import { NavigationService } from '../../core/services/navigation.service';
import { TenantService } from '../../core/services/tenant.service';
import { NotificationsService } from '../../core/services/notifications.service';
import { ScheduledJobRun, ScheduledJobRunStatus } from '../../core/services/admin.service';
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
  runs$!: Observable<ScheduledJobRun[]>;

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

  isUnseen(run: ScheduledJobRun): boolean {
    if (run.status === 'RUNNING' || !run.endedAt) return false;
    // Mirrors NotificationsService.computeUnseen so the row styling
    // stays consistent with the badge count.
    try {
      const raw = localStorage.getItem('medfund_notifications_last_seen');
      const ts = raw ? new Date(raw).getTime() : 0;
      return new Date(run.endedAt).getTime() > ts;
    } catch {
      return true;
    }
  }

  statusColor(s: ScheduledJobRunStatus): string {
    return { RUNNING: 'blue', SUCCESS: 'green', FAILED: 'red' }[s] ?? 'gray';
  }

  statusVerb(s: ScheduledJobRunStatus): string {
    return { RUNNING: 'Running', SUCCESS: 'Completed', FAILED: 'Failed' }[s] ?? s;
  }

  jobLabel(run: ScheduledJobRun): string {
    // The server doesn't include the friendly job name in the run row
    // — we just have the run id. Use a short version for now; the full
    // /jobs page (future) can join to scheduled_job_configs.name.
    return 'Job ' + run.id.slice(0, 8);
  }

  humanDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    const s = Math.round(ms / 1000);
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    if (m < 60) return `${m}m${s % 60}s`;
    const h = Math.floor(m / 60);
    return `${h}h${m % 60}m`;
  }

  ngOnInit(): void {
    this.userInfo = this.navService.getUserInfo();
    this.isSuperAdmin = (this.keycloak.getUserRoles(true) ?? []).includes('super_admin');

    // Notifications: start the shared polling loop + bind the streams
    // the template binds via async pipe.
    this.notifications.start();
    this.unseenCount$ = this.notifications.badge;
    this.runs$ = this.notifications.list;

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
