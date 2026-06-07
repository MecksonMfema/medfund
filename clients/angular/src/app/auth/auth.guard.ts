import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { KeycloakService } from 'keycloak-angular';
import { firstValueFrom } from 'rxjs';
import { filter, take, timeout, catchError } from 'rxjs/operators';
import { of } from 'rxjs';
import { PermissionService } from '../core/security/permission.service';
import { PermissionKey } from '../core/security/permissions';

export const authGuard: CanActivateFn = async () => {
  const keycloak = inject(KeycloakService);

  const isAuthenticated = await keycloak.isLoggedIn();
  if (!isAuthenticated) {
    await keycloak.login();
    return false;
  }
  return true;
};

export const roleGuard = (requiredRoles: string[]): CanActivateFn => {
  return async () => {
    const keycloak = inject(KeycloakService);
    const router = inject(Router);

    const isAuthenticated = await keycloak.isLoggedIn();
    if (!isAuthenticated) {
      await keycloak.login();
      return false;
    }

    const userRoles = keycloak.getUserRoles(true);
    const hasRole = requiredRoles.some((role) => userRoles.includes(role));
    if (!hasRole) {
      console.log('[RoleGuard] Access denied. Required:', requiredRoles, 'User has:', userRoles);
      router.navigate(['/unauthorized']);
      return false;
    }
    return true;
  };
};

/**
 * Root redirector — dispatches the {@code /} route based on the user's
 * Keycloak realm roles. Replaces the legacy {@code RoleRedirectComponent},
 * which did the same job in a component lifecycle hook. Implementing it as a
 * guard keeps the redirect off the user's history (no flash of redirect
 * page) and lets the router create a {@link UrlTree} directly.
 *
 * <ul>
 *   <li>{@code super_admin} → {@code /platform/dashboard}</li>
 *   <li>any other authenticated user → {@code /tenant/dashboard} (operational portal)</li>
 *   <li>unauthenticated → Keycloak login</li>
 *   <li>authenticated but no roles → {@code /unauthorized}</li>
 * </ul>
 */
export const rootRedirectGuard: CanActivateFn = async (): Promise<UrlTree | boolean> => {
  const keycloak = inject(KeycloakService);
  const router = inject(Router);

  const isAuthenticated = await keycloak.isLoggedIn();
  if (!isAuthenticated) {
    await keycloak.login();
    return false;
  }

  let roles: string[] = [];
  try {
    roles = keycloak.getUserRoles(true);
  } catch {
    return router.createUrlTree(['/unauthorized']);
  }

  if (roles.includes('super_admin')) {
    return router.createUrlTree(['/platform/dashboard']);
  }
  if (roles.length > 0) {
    return router.createUrlTree(['/tenant/dashboard']);
  }
  return router.createUrlTree(['/unauthorized']);
};

/**
 * Fine-grained guard backed by the canonical permission catalogue. Allows the
 * route when the user holds AT LEAST ONE of the supplied permission keys.
 *
 * <p>Use this instead of {@link roleGuard} for operational routes — the
 * permissions are tenant-defined and resolve through the same
 * {@link PermissionService} the {@code *hasPermission} directive uses, so
 * route gates and UI gates stay in lock-step.
 *
 * <p>The guard waits up to 2 s for the first permission set to arrive (handy
 * during cold app boot when the {@code /me/permissions} fetch is in flight)
 * before deciding. After the timeout it treats the user as unprivileged so
 * a hung backend can't lock them out of /unauthorized.
 */
export const permissionGuard = (required: ReadonlyArray<PermissionKey | string>): CanActivateFn => {
  return async () => {
    const keycloak = inject(KeycloakService);
    const router = inject(Router);
    const permissions = inject(PermissionService);

    const isAuthenticated = await keycloak.isLoggedIn();
    if (!isAuthenticated) {
      await keycloak.login();
      return false;
    }

    // Super admin bypass — platform admins implicitly hold every permission
    // and shouldn't be blocked by the tenant-scoped permission set (which is
    // empty for platform-only users by design, see PermissionService docs).
    // Also fixes a reload race: on cold boot the tenant restore from
    // sessionStorage can finish after this guard's 2 s timeout, leaving a
    // super admin staring at /unauthorized despite having every right.
    if (keycloak.getUserRoles(true).includes('super_admin')) return true;

    // Fast path — already loaded.
    if (required.some(p => permissions.has(p))) return true;

    // Slow path — wait for the first non-empty set, then re-check. The
    // BehaviorSubject emits the (possibly empty) initial value immediately,
    // so filter for a populated set OR fall through after the timeout.
    const set = await firstValueFrom(
      permissions.permissions$.pipe(
        filter(s => s.size > 0),
        take(1),
        timeout({ first: 2000 }),
        catchError(() => of(new Set<string>())),
      ),
    );
    const allow = required.some(p => set.has(p));
    if (!allow) {
      console.log('[PermissionGuard] denied. Required:', required, 'User has:', Array.from(set));
      router.navigate(['/unauthorized']);
      return false;
    }
    return true;
  };
};
