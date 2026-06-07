import { BehaviorSubject, Observable, of } from 'rxjs';
import { PermissionService } from '../core/security/permission.service';
import { PermissionKey } from '../core/security/permissions';

/**
 * Hand-rolled stub for {@link PermissionService} that lets tests:
 *   - seed an initial permission set (super admin and / or fine-grained keys)
 *   - flip the set at runtime via {@link emit} to exercise reactive directives
 *   - assert call counts on {@code refresh()} when needed
 *
 * Mirrors the public surface of the real service so {@code provide: PermissionService, useValue: new MockPermissionService(...)}
 * is a drop-in swap in TestBed configs.
 */
export class MockPermissionService implements Partial<PermissionService> {
  private subject: BehaviorSubject<ReadonlySet<string>>;
  private superAdmin: boolean;

  refreshCalls = 0;

  permissions$: Observable<ReadonlySet<string>>;

  constructor(initial: ReadonlyArray<string> = [], opts: { superAdmin?: boolean } = {}) {
    this.subject = new BehaviorSubject<ReadonlySet<string>>(new Set(initial));
    this.superAdmin = opts.superAdmin ?? false;
    this.permissions$ = this.subject.asObservable();
  }

  isSuperAdmin = (): boolean => this.superAdmin;

  has = (permission: PermissionKey | string): boolean => {
    if (this.superAdmin) return true;
    return this.subject.getValue().has(permission as string);
  };

  hasAny = (perms: ReadonlyArray<PermissionKey | string>): boolean => {
    if (this.superAdmin) return true;
    const held = this.subject.getValue();
    return perms.some(p => held.has(p as string));
  };

  refresh = (): Observable<ReadonlySet<string>> => {
    this.refreshCalls += 1;
    return of(this.subject.getValue());
  };

  snapshot = (): ReadonlySet<string> => this.subject.getValue();

  // ── Test-only helpers ────────────────────────────────────────────────────
  emit(perms: ReadonlyArray<string>): void {
    this.subject.next(new Set(perms));
  }

  setSuperAdmin(v: boolean): void { this.superAdmin = v; }
}

export function provideMockPermissions(
  initial: ReadonlyArray<string> = [],
  opts: { superAdmin?: boolean } = {},
) {
  return { provide: PermissionService, useValue: new MockPermissionService(initial, opts) };
}
