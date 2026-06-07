import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, of } from 'rxjs';
import { catchError, distinctUntilChanged, map, switchMap, tap } from 'rxjs/operators';
import { KeycloakService } from 'keycloak-angular';
import { ApiService } from '../services/api.service';
import { TenantService } from '../services/tenant.service';
import { PermissionKey } from './permissions';

/**
 * Loads the calling user's effective permissions from
 * {@code GET /api/v1/me/permissions} and exposes them to guards, the
 * {@code *hasPermission} structural directive, and any imperative checks.
 *
 * <p>The set refreshes whenever the active tenant changes (via
 * {@link TenantService#tenant$}) so switching tenants doesn't carry
 * permissions across — and {@link #refresh()} forces a re-fetch after
 * a role-permission edit so the admin sees their own change immediately
 * without waiting for the 60-s cache TTL on the server.
 *
 * <p>Platform-only users (no tenant) get an empty set — they rely on the
 * existing role-based {@code roleGuard(['super_admin'])} for portal access,
 * not on the permission system.
 */
@Injectable({ providedIn: 'root' })
export class PermissionService {
  private readonly permissions$$ = new BehaviorSubject<ReadonlySet<string>>(new Set());

  /** Replays the latest set; emits whenever the tenant changes or refresh() is called. */
  readonly permissions$: Observable<ReadonlySet<string>> = this.permissions$$.asObservable();

  constructor(
    private api: ApiService,
    private tenantService: TenantService,
    private keycloak: KeycloakService,
  ) {
    // Refetch whenever the tenant changes — including app boot, when
    // tenant.service restores the cached tenant from sessionStorage.
    this.tenantService.tenant$
      .pipe(
        map(t => t?.id ?? null),
        distinctUntilChanged(),
        switchMap(tenantId => tenantId ? this.fetch() : of(new Set<string>())),
      )
      .subscribe(set => this.permissions$$.next(set));
  }

  /** True when the JWT carries the {@code super_admin} realm role.
   *  Super admins implicitly hold every right, regardless of the
   *  tenant-scoped permission set on {@link #permissions$} (which is
   *  empty for platform-only users by design). */
  isSuperAdmin(): boolean {
    return (this.keycloak.getUserRoles(true) ?? []).includes('super_admin');
  }

  /**
   * Synchronous check used by route guards and the {@code *hasPermission}
   * directive. Reads the most recent fetch result without re-querying.
   * Super admins always pass.
   */
  has(permission: PermissionKey | string): boolean {
    if (this.isSuperAdmin()) return true;
    return this.permissions$$.getValue().has(permission);
  }

  /** Synchronous "any of" check — true when the user holds at least one
   *  key, or is a super admin. */
  hasAny(permissions: ReadonlyArray<PermissionKey | string>): boolean {
    if (this.isSuperAdmin()) return true;
    const held = this.permissions$$.getValue();
    return permissions.some(p => held.has(p));
  }

  /** Re-fetch from the server. Call after a role-permission save so the UI updates without waiting on TTL. */
  refresh(): Observable<ReadonlySet<string>> {
    return this.fetch().pipe(tap(set => this.permissions$$.next(set)));
  }

  /** Returns the full set as a snapshot — useful for tests and debug logging. */
  snapshot(): ReadonlySet<string> {
    return this.permissions$$.getValue();
  }

  private fetch(): Observable<ReadonlySet<string>> {
    return this.api.get<string[]>('/me/permissions').pipe(
      map(arr => new Set<string>(Array.isArray(arr) ? arr : [])),
      // Failures must not block the app — log and treat as no permissions.
      // Guards then redirect the user to /unauthorized cleanly instead of
      // surfacing a stack trace.
      catchError(err => {
        console.warn('[PermissionService] failed to load permissions', err);
        return of(new Set<string>());
      }),
    );
  }
}
