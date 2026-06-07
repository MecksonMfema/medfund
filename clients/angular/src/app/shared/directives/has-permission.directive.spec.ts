import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { HasPermissionDirective } from './has-permission.directive';
import { PermissionService } from '../../core/security/permission.service';
import { MockPermissionService } from '../../_test-utils/mock-permission.service';

@Component({
  standalone: true,
  imports: [HasPermissionDirective],
  template: `
    <div *hasPermission="permission" class="gated">Visible</div>
  `,
})
class HostComponent {
  permission: string | ReadonlyArray<string> = 'claims:view';
}

describe('HasPermissionDirective', () => {
  let fixture: ComponentFixture<HostComponent>;
  let permissions: MockPermissionService;

  function setup(initial: ReadonlyArray<string> = [], superAdmin = false): void {
    permissions = new MockPermissionService(initial, { superAdmin });
    TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [{ provide: PermissionService, useValue: permissions }],
    });
    fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
  }

  function gatedEl(): HTMLElement | null {
    const debug = fixture.debugElement.query(By.css('.gated'));
    return debug?.nativeElement ?? null;
  }

  it('hides the template when the user lacks the permission', () => {
    setup([]);
    expect(gatedEl()).toBeNull();
  });

  it('renders the template when the user holds the permission', () => {
    setup(['claims:view']);
    expect(gatedEl()?.textContent?.trim()).toBe('Visible');
  });

  it('shows automatically when the permission set later includes the key (reactive)', () => {
    setup([]);
    expect(gatedEl()).toBeNull();

    permissions.emit(['claims:view']);
    fixture.detectChanges();

    expect(gatedEl()?.textContent?.trim()).toBe('Visible');
  });

  it('hides automatically when the permission is revoked (reactive)', () => {
    setup(['claims:view']);
    expect(gatedEl()).not.toBeNull();

    permissions.emit([]);
    fixture.detectChanges();

    expect(gatedEl()).toBeNull();
  });

  it('renders for super admins regardless of the permission set', () => {
    setup([], true);
    expect(gatedEl()?.textContent?.trim()).toBe('Visible');
  });

  it('supports an array input — any-of semantics', () => {
    setup(['billing:view']);
    fixture.componentInstance.permission = ['claims:view', 'billing:view'];
    fixture.detectChanges();
    expect(gatedEl()).not.toBeNull();
  });

  it('does not render twice on duplicate emissions', () => {
    setup(['claims:view']);
    permissions.emit(['claims:view']);
    fixture.detectChanges();
    permissions.emit(['claims:view']);
    fixture.detectChanges();

    const matches = fixture.debugElement.queryAll(By.css('.gated'));
    expect(matches.length).toBe(1);
  });

  it('unsubscribes on destroy — no emissions after ngOnDestroy', () => {
    setup(['claims:view']);
    fixture.destroy();

    // No assertion errors expected; if the directive didn't unsubscribe it
    // would touch the destroyed view container and throw.
    expect(() => permissions.emit([])).not.toThrow();
  });
});
