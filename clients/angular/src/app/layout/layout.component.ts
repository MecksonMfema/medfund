import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { Subscription } from 'rxjs';
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
  private sub?: Subscription;

  constructor(
    private navService: NavigationService,
  ) {}

  ngOnInit(): void {
    // We deliberately do NOT clearTenant() here. The tenant interceptor
    // already gates X-Tenant-ID by route URL (only attaches it on /tenant/*
    // routes), so a platform admin browsing /platform sends no tenant
    // header regardless of TenantService state. Wiping the cached tenant
    // would force a re-pick every time a super_admin bounces between the
    // platform portal and a tenant they're currently administering.

    this.sub = this.navService.collapsed$.subscribe(
      (c) => (this.collapsed = c)
    );
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }
}
