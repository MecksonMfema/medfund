import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { Subscription } from 'rxjs';
import { SidebarComponent } from './sidebar/sidebar.component';
import { HeaderComponent } from './header/header.component';
import { ProgressBarComponent } from '../shared/components/progress-bar/progress-bar.component';
import { NavigationService } from '../core/services/navigation.service';
import { TenantService } from '../core/services/tenant.service';

@Component({
  selector: 'app-layout',
  standalone: true,
  imports: [CommonModule, RouterOutlet, SidebarComponent, HeaderComponent, ProgressBarComponent],
  templateUrl: './layout.component.html',
  styleUrl: './layout.component.scss',
})
export class LayoutComponent implements OnInit, OnDestroy {
  collapsed = false;
  private sub?: Subscription;

  constructor(
    private navService: NavigationService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    // Clear any leftover tenant context so the interceptor does NOT send
    // X-Tenant-ID on platform admin requests.
    this.tenantService.clearTenant();

    this.sub = this.navService.collapsed$.subscribe(
      (c) => (this.collapsed = c)
    );
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }
}
