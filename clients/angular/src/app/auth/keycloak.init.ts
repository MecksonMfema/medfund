import { KeycloakService } from 'keycloak-angular';
import { environment } from '../../environments/environment';
import { AdminService } from '../core/services/admin.service';
import { BrandingService } from '../core/services/branding.service';
import { TenantService } from '../core/services/tenant.service';
import { bootstrapTenantFromJwt } from './tenant-bootstrap';

const GATEWAY_URL = environment.apiBaseUrl.replace('/api/v1', '');

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
  return () =>
    keycloak
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
