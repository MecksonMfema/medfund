import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { Subscription } from 'rxjs';
import { KeycloakService } from 'keycloak-angular';
import { NavigationService } from '../../core/services/navigation.service';
import { NavGroup, UserInfo } from '../../core/models/navigation.model';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { clearSession } from '../../auth/keycloak.init';

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [CommonModule, RouterLink, RouterLinkActive, IconComponent],
  templateUrl: './sidebar.component.html',
  styleUrl: './sidebar.component.scss',
})
export class SidebarComponent implements OnInit, OnDestroy {
  collapsed = false;
  navGroups: NavGroup[] = [];
  userInfo: UserInfo = { fullName: 'User', initials: 'U', email: '', roleLabel: 'User' };

  private sub?: Subscription;

  constructor(
    private navService: NavigationService,
    private keycloak: KeycloakService,
  ) {}

  ngOnInit(): void {
    this.navGroups = this.navService.getNavigation();
    this.userInfo = this.navService.getUserInfo();
    this.sub = this.navService.collapsed$.subscribe(
      (c) => (this.collapsed = c)
    );
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
