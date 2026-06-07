import { Page, Route } from '@playwright/test';

/**
 * Builds a JWT-shaped string the Angular app's KeycloakService can decode at
 * runtime. The signature byte is irrelevant — `keycloak-js` only validates the
 * signature when its internal `validateAccessToken` path is wired up, which we
 * stub out below by intercepting the realm config endpoint.
 */
function fakeJwt(claims: Record<string, unknown>): string {
  const header = Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT', kid: 'test' }))
    .toString('base64url');
  const payload = Buffer.from(JSON.stringify({
    iss: 'http://localhost:9080/realms/medfund-platform',
    aud: 'medfund-web',
    exp: Math.floor(Date.now() / 1000) + 3600,
    iat: Math.floor(Date.now() / 1000),
    ...claims,
  })).toString('base64url');
  const signature = 'test-signature';
  return `${header}.${payload}.${signature}`;
}

export interface AuthIdentity {
  /** Realm roles — drives `roleGuard` + the super-admin bypass in PermissionService. */
  realmRoles: string[];
  /** Sub claim — used by SchemeService.create as actorId in the audit envelope. */
  sub?: string;
  /** Tenant claim — forwarded as X-Tenant-ID by the API gateway. */
  tenantId?: string;
  /** Email — shown in the header dropdown. */
  email?: string;
  /** Given/family names — drive initials in the avatar. */
  givenName?: string;
  familyName?: string;
}

/**
 * Bypass Keycloak's OIDC handshake at app boot. The harness injects a stub
 * token into `window.__MEDFUND_E2E_TOKEN__` via `addInitScript`; the
 * production code in {@code keycloak.init.ts} reads it and short-circuits
 * the real `keycloak.init()` path. Pure SPA-side test override — no real
 * network calls to a Keycloak instance, no iframe SSO, no redirect dance.
 *
 * <p>The well-known and `/api/v1/session` route stubs are kept as a safety
 * net so any code that still tries to reach Keycloak (e.g. proactive token
 * refresh) doesn't 404-loop.
 */
export async function stubKeycloak(page: Page, identity: AuthIdentity): Promise<void> {
  const sub = identity.sub ?? '00000000-0000-4000-8000-000000000099';
  const tenantId = identity.tenantId ?? '11111111-1111-1111-1111-111111111111';
  const email = identity.email ?? 'operator@example.com';
  const givenName = identity.givenName ?? 'Test';
  const familyName = identity.familyName ?? 'Operator';

  const accessToken = fakeJwt({
    sub,
    tenant_id: tenantId,
    email,
    given_name: givenName,
    family_name: familyName,
    preferred_username: email,
    realm_access: { roles: identity.realmRoles },
    resource_access: { 'medfund-web': { roles: identity.realmRoles } },
  });
  const refreshToken = fakeJwt({ sub, typ: 'Refresh' });

  // The single source of truth for the harness — keycloak.init.ts reads this
  // and bypasses its real init dance. Set BEFORE the page loads so the
  // APP_INITIALIZER catches it on the first run.
  await page.addInitScript((token) => {
    (window as unknown as { __MEDFUND_E2E_TOKEN__: unknown }).__MEDFUND_E2E_TOKEN__ = token;
  }, {
    accessToken,
    refreshToken,
    realmRoles: identity.realmRoles,
    sub,
    tenantId,
    email,
    givenName,
    familyName,
  });

  // Realm configuration endpoint — keycloak-js fetches this during init to
  // discover the auth/token endpoints. Returning a stub points it at our
  // intercepted token endpoint instead of real Keycloak.
  await page.route('**/realms/medfund-platform/.well-known/openid-configuration', (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        issuer: 'http://localhost:9080/realms/medfund-platform',
        authorization_endpoint: `${page.url() || 'http://localhost:9080'}/__stub/auth`,
        token_endpoint: `${page.url() || 'http://localhost:9080'}/__stub/token`,
        end_session_endpoint: `${page.url() || 'http://localhost:9080'}/__stub/logout`,
        jwks_uri: `${page.url() || 'http://localhost:9080'}/__stub/jwks`,
        response_types_supported: ['code'],
        subject_types_supported: ['public'],
        id_token_signing_alg_values_supported: ['RS256'],
      }),
    }),
  );

  // The SSO 3rd-party-cookies probe iframe. Returning an empty page short-
  // circuits the check without keycloak-js timing out.
  await page.route('**/realms/medfund-platform/protocol/openid-connect/3p-cookies/**', (route: Route) =>
    route.fulfill({ status: 200, contentType: 'text/html', body: '<html></html>' }),
  );

  // The login redirect — when `onLoad: 'login-required'` would normally bounce
  // to Keycloak, intercept and 302 back with a fake code so the adapter
  // proceeds to exchange it at the token endpoint.
  await page.route('**/realms/medfund-platform/protocol/openid-connect/auth**', (route: Route) => {
    const url = new URL(route.request().url());
    const redirectUri = url.searchParams.get('redirect_uri');
    const state = url.searchParams.get('state');
    const sessionState = '00000000-test-sess';
    const code = 'stub-auth-code';
    const target = `${redirectUri}?state=${state}&session_state=${sessionState}&code=${code}`;
    return route.fulfill({ status: 302, headers: { location: target } });
  });

  // Token exchange — accepts any code and returns the pre-built fake JWT.
  await page.route('**/realms/medfund-platform/protocol/openid-connect/token', (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        access_token: accessToken,
        refresh_token: refreshToken,
        expires_in: 3600,
        refresh_expires_in: 7200,
        token_type: 'Bearer',
        'not-before-policy': 0,
        session_state: '00000000-test-sess',
        scope: 'openid profile email',
      }),
    }),
  );

  // Logout — keycloak-js posts to this on user-initiated logout. Return 204
  // so the UI proceeds to its post-logout redirect.
  await page.route('**/realms/medfund-platform/protocol/openid-connect/logout', (route: Route) =>
    route.fulfill({ status: 204, body: '' }),
  );

  // The legacy session cookie API in keycloak.init.ts — short-circuit so
  // the SPA does not try to talk to a real gateway during auth bootstrap.
  await page.route('**/api/v1/session', (route: Route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '{"status":"ok"}' }),
  );
}
