import { KeycloakService } from 'keycloak-angular';
import { environment } from '../../environments/environment';
import { AdminService } from '../core/services/admin.service';
import { BrandingService } from '../core/services/branding.service';
import { TenantService } from '../core/services/tenant.service';
import { bootstrapTenantFromJwt } from './tenant-bootstrap';

const GATEWAY_URL = environment.apiBaseUrl.replace('/api/v1', '');

/**
 * E2E test escape hatch. When the Playwright fixture sets
 * {@code window.__MEDFUND_E2E_TOKEN__} via {@code addInitScript}, we skip
 * Keycloak's real init dance entirely and seed a stub authenticated state.
 * Production builds never see this branch — the harness sets the flag
 * before any app code runs.
 */
interface E2EWindow {
  __MEDFUND_E2E_TOKEN__?: {
    accessToken: string;
    refreshToken: string;
    realmRoles: string[];
    sub: string;
    tenantId: string;
    email: string;
    givenName: string;
    familyName: string;
  };
}

function getE2EOverride(): E2EWindow['__MEDFUND_E2E_TOKEN__'] | undefined {
  if (typeof window === 'undefined') return undefined;
  return (window as unknown as E2EWindow).__MEDFUND_E2E_TOKEN__;
}

// Refresh the token (and re-set the HTTP-only cookie) when less than this
// many seconds remain. Must be greater than PROACTIVE_CHECK_INTERVAL_MS / 1000
// so the interval always catches the window before the cookie expires.
const REFRESH_BUFFER_SECONDS = 60;

// How often (ms) to proactively check whether the token needs refreshing.
const PROACTIVE_CHECK_INTERVAL_MS = 30_000;

export function initializeKeycloak(
  keycloak: KeycloakService,
  tenantService: TenantService,
  adminService: AdminService,
  brandingService: BrandingService,
): () => Promise<boolean> {
  return async () => {
    const e2e = getE2EOverride();
    if (e2e) {
      // Test harness path: bypass keycloak.init() and stub the KeycloakService
      // surface that downstream code calls (getToken, getUserRoles, isLoggedIn,
      // getKeycloakInstance, etc.). The stub returns deterministic data
      // assembled by the Playwright fixture.
      const stub = keycloak as unknown as Record<string, unknown>;
      stub['isLoggedIn']    = () => true;
      stub['getToken']      = () => Promise.resolve(e2e.accessToken);
      stub['getUserRoles']  = () => [...e2e.realmRoles];
      stub['updateToken']   = () => Promise.resolve(true);
      stub['isTokenExpired'] = () => false;
      stub['logout']        = () => Promise.resolve();
      stub['login']         = () => Promise.resolve();
      stub['getKeycloakInstance'] = () => ({
        tokenParsed: {
          sub: e2e.sub,
          tenant_id: e2e.tenantId,
          email: e2e.email,
          given_name: e2e.givenName,
          family_name: e2e.familyName,
          preferred_username: e2e.email,
          realm_access: { roles: [...e2e.realmRoles] },
        },
        token: e2e.accessToken,
      });

      await bootstrapTenantFromJwt(keycloak, tenantService, adminService, brandingService);
      return true;
    }

    return keycloak
      .init({
        config: {
          url: environment.keycloak.url,
          realm: environment.keycloak.realm,
          clientId: environment.keycloak.clientId,
        },
        initOptions: {
          onLoad: 'login-required',
          pkceMethod: 'S256',
          checkLoginIframe: false,
        },
        enableBearerInterceptor: false,
        bearerExcludedUrls: [],
      })
      .then(async (authenticated) => {
        if (authenticated) {
          await establishSession(keycloak);

          // Seed TenantService from the JWT before any route activates.
          // Without this, permissionGuard runs before TenantLayoutComponent
          // has had a chance to call setTenant(), so PermissionService
          // hasn't fetched /me/permissions yet — guard times out at 2 s
          // and 403s the first navigation. Awaited here so the bootstrap
          // is finished by the time APP_INITIALIZER resolves.
          await bootstrapTenantFromJwt(keycloak, tenantService, adminService, brandingService);

          // Proactive refresh: check every 30 s and renew the cookie before
          // it expires. This prevents the gap caused by onTokenExpired firing
          // after expiry, and is resilient to background-tab timer throttling.
          setInterval(async () => {
            if (keycloak.isTokenExpired(REFRESH_BUFFER_SECONDS)) {
              await refreshSession(keycloak);
            }
          }, PROACTIVE_CHECK_INTERVAL_MS);

          // Safety-net: fires if the interval missed a refresh (e.g. the tab
          // was suspended for longer than REFRESH_BUFFER_SECONDS).
          keycloak.getKeycloakInstance().onTokenExpired = () => refreshSession(keycloak);
        }
        return authenticated;
      });
  };
}

async function refreshSession(keycloak: KeycloakService): Promise<void> {
  try {
    await keycloak.updateToken(REFRESH_BUFFER_SECONDS);
    await establishSession(keycloak);
  } catch {
    keycloak.login();
  }
}

async function establishSession(keycloak: KeycloakService): Promise<void> {
  const token = await keycloak.getToken();
  await fetch(`${GATEWAY_URL}/auth/session`, {
    method: 'POST',
    credentials: 'include',
    headers: { Authorization: `Bearer ${token}` },
  });
}

export async function clearSession(): Promise<void> {
  await fetch(`${GATEWAY_URL}/auth/logout`, {
    method: 'POST',
    credentials: 'include',
  });
}
