import { BehaviorSubject, Observable } from 'rxjs';
import { NavigationService } from '../core/services/navigation.service';
import { UserInfo } from '../core/models/navigation.model';

export class MockNavigationService implements Partial<NavigationService> {
  private collapsed = new BehaviorSubject<boolean>(false);
  collapsed$: Observable<boolean> = this.collapsed.asObservable();

  userInfo: UserInfo = { fullName: 'Test User', initials: 'TU', email: 'test@example.com', roleLabel: 'Operator' };

  toggleCalls = 0;

  toggleSidebar(): void {
    this.toggleCalls += 1;
    this.collapsed.next(!this.collapsed.value);
  }

  setSidebarCollapsed(value: boolean): void { this.collapsed.next(value); }
  get isCollapsed(): boolean { return this.collapsed.value; }
  getUserInfo(): UserInfo { return this.userInfo; }
}

export function provideMockNavigation(svc: MockNavigationService = new MockNavigationService()) {
  return { provide: NavigationService, useValue: svc };
}
