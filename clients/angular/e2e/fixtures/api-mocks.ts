import { Page, Route, Request } from '@playwright/test';

type Body = unknown;
type Handler = (request: Request) => Body | Promise<Body>;

interface Stub {
  method: string;
  pathRegex: RegExp;
  handler: Handler;
  status: number;
}

/**
 * Lightweight router on top of `page.route('**\/api/v1/**', …)`.
 *
 * <p>Default behaviour: every API call returns 404, so an un-stubbed network
 * hit fails the test loudly rather than silently passing on whatever the
 * underlying browser cache contains.
 *
 * <p>The `respond('GET /me/permissions', …)` syntax mirrors how the production
 * code reads — tests are scannable side-by-side with the service definitions.
 */
export class ApiMocks {
  private stubs: Stub[] = [];

  constructor(private page: Page) {}

  /** Install the catch-all route. Must be called before navigation. */
  async install(): Promise<void> {
    await this.page.route('**/api/v1/**', async (route: Route, request: Request) => {
      const url = new URL(request.url());
      const path = url.pathname.replace(/^.*\/api\/v1/, '');
      const method = request.method().toUpperCase();

      // Iterate newest-first so test-level overrides win over fixture defaults.
      for (let i = this.stubs.length - 1; i >= 0; i--) {
        const s = this.stubs[i];
        if (s.method === method && s.pathRegex.test(path)) {
          const body = await s.handler(request);
          await route.fulfill({
            status: s.status,
            contentType: 'application/json',
            body: typeof body === 'string' ? body : JSON.stringify(body),
          });
          return;
        }
      }
      await route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'https://medfund.healthcare/errors/e2e-unstubbed',
          title: 'E2E unstubbed call',
          detail: `No stub registered for ${method} ${path}`,
          status: 404,
        }),
      });
    });
  }

  /**
   * Register a stub. Spec string is `METHOD /path` — the path is matched as a
   * regex against the URL pathname stripped of the `/api/v1` prefix.
   */
  respond(spec: string, body: Body | Handler, status = 200): this {
    const [methodRaw, pathPattern] = spec.split(/\s+/, 2);
    if (!methodRaw || !pathPattern) {
      throw new Error(`invalid spec "${spec}" — expected "METHOD /path"`);
    }
    // Convert the pattern (with optional :params) into a regex anchored at start.
    const pathRegex = new RegExp(
      '^' + pathPattern.replace(/:[^/]+/g, '[^/]+').replace(/\*/g, '.*') + '/?$',
    );
    const handler: Handler = typeof body === 'function' ? (body as Handler) : () => body;
    this.stubs.push({ method: methodRaw.toUpperCase(), pathRegex, handler, status });
    return this;
  }

  /** Inspect what was registered — useful when debugging an unexpected 404. */
  listStubs(): readonly string[] {
    return this.stubs.map(s => `${s.method} ${s.pathRegex.source}`);
  }
}

/** Default API stubs every test needs — overlay-able with `apiMocks.respond(...)`. */
export function defaults(apiMocks: ApiMocks, opts: {
  tenantId: string;
  tenantName?: string;
  tenantSlug?: string;
  permissions?: string[];
  insuranceLines?: string[];
}): void {
  const tenantId = opts.tenantId;
  const tenantName = opts.tenantName ?? 'Acme Health';
  const tenantSlug = opts.tenantSlug ?? 'acme';
  const insuranceLines = opts.insuranceLines ?? ['HEALTH'];
  const permissions = opts.permissions ?? [];

  apiMocks.respond('GET /me/permissions', permissions);

  apiMocks.respond(`GET /tenants/${tenantId}`, {
    id: tenantId,
    name: tenantName,
    slug: tenantSlug,
    status: 'active',
    timezone: 'Africa/Harare',
    insuranceLines,
    providerRegLabel: 'AHFOZ / Practice Number',
    defaultCurrencyCode: 'USD',
    schemeLabelSingular: 'Scheme',
    schemeLabelPlural: 'Schemes',
    drugClaimsEnabled: true,
    membershipModel: 'BOTH',
    settings: JSON.stringify({ insuranceLines }),
  });

  apiMocks.respond(`GET /tenants/${tenantId}/branding`, {
    tenantId,
    logoUrl: '',
    primaryColor: '#2EC4B6',
    secondaryColor: '#00B4D8',
  });

  // Permission-resolver hits these on guard activation. Returning empty
  // is fine — has() falls back to false unless super_admin (role-based).
  apiMocks.respond(`GET /tenants/${tenantId}/currencies`, [
    { id: 'c1', tenantId, currencyCode: 'USD', isDefault: true, isActive: true,
      isBillingCurrency: true, isClaimsCurrency: true, isPaymentCurrency: true,
      exchangeRateSource: 'manual' },
  ]);
}
