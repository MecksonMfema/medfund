import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { Subscription, filter } from 'rxjs';
import { SidebarComponent } from './sidebar/sidebar.component';
import { HeaderComponent } from './header/header.component';
import { ProgressBarComponent } from '../shared/components/progress-bar/progress-bar.component';
import { ToastContainerComponent } from '../shared/components/toast/toast-container.component';
import { ConfirmDialogComponent } from '../shared/components/confirm-dialog/confirm-dialog.component';
import { NavigationService } from '../core/services/navigation.service';

@Component({
  selector: 'app-layout',
  standalone: true,
  imports: [CommonModule, RouterOutlet, SidebarComponent, HeaderComponent, ProgressBarComponent, ToastContainerComponent, ConfirmDialogComponent],
  templateUrl: './layout.component.html',
  styleUrl: './layout.component.scss',
})
export class LayoutComponent implements OnInit, OnDestroy {
  collapsed = false;
  /**
   * Routes can opt the main content area out of its standard page padding
   * by setting {@code data: { fullbleed: true }}. Matches the same flag on
   * TenantLayoutComponent so list pages under /platform/* can span
   * edge-to-edge the same way tenant list pages do.
   */
  fullbleed = false;
  private subs: Subscription[] = [];

  constructor(
    private navService: NavigationService,
    private router: Router,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    // We deliberately do NOT clearTenant() here. The tenant interceptor
    // already gates X-Tenant-ID by route URL (only attaches it on /tenant/*
    // routes), so a platform admin browsing /platform sends no tenant
    // header regardless of TenantService state. Wiping the cached tenant
    // would force a re-pick every time a super_admin bounces between the
    // platform portal and a tenant they're currently administering.

    this.subs.push(
      this.navService.collapsed$.subscribe((c) => (this.collapsed = c)),
    );

    this.subs.push(
      this.router.events
        .pipe(filter(e => e instanceof NavigationEnd))
        .subscribe(() => { this.fullbleed = this.resolveFullbleed(); }),
    );
    this.fullbleed = this.resolveFullbleed();
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
  }

  /**
   * Walks the activated route tree and returns the deepest
   * {@code fullbleed} route-data flag, defaulting to {@code false} so
   * standard padded layouts still apply when nothing is declared.
   */
  private resolveFullbleed(): boolean {
    let r = this.route;
    let pick = false;
    while (r.firstChild) {
      r = r.firstChild;
      const v = r.snapshot.data['fullbleed'];
      if (typeof v === 'boolean') pick = v;
    }
    return pick;
  }
}
