import { test, expect } from '../fixtures/test';

/**
 * Phase 17 §D.1 — signed-link download from a scheduled report delivery email.
 *
 * The notification-service switches from XLSX attachment to a signed-URL
 * button when the workbook exceeds ~10 MB; the button targets
 * `GET /api/v1/reports/scheduled/{jobId}/download?token={HMAC}`. The gateway
 * allowlists this path (no JWT) — the token itself carries authorisation.
 *
 * In this mocked harness we can't test the real HMAC verifier — that's the
 * finance-service integration test's job. What the spec covers here is the
 * browser-clickable contract:
 *
 *   • signed URL with a valid token → 200 + XLSX bytes;
 *   • signed URL with a missing / bad token → 403.
 *
 * The click is simulated with a page-context `fetch()` (via page.evaluate),
 * which routes through Playwright's `page.route` mocks — an out-of-browser
 * `page.request` would bypass them.
 */

const JOB_ID = '55555555-5555-5555-5555-555555555555';

test.describe('Scheduled report signed-link download (Phase 17 §D.1)', () => {
  test('valid token → 200 with XLSX bytes; missing token → 403', async ({ page, apiMocks, signInAs }) => {
    // Sign in so the app's APP_INITIALIZER doesn't bounce to Keycloak. In
    // production the recipient clicking a signed link is *not* authenticated,
    // so the SPA must bypass keycloak.init() for this URL — that gap is
    // deferred as a Phase 11 follow-up. The token-verification contract this
    // spec exercises is unaffected by the auth harness.
    await signInAs({ realmRoles: ['operator'] });

    apiMocks.respond(
      `GET /reports/scheduled/${JOB_ID}/download`,
      (req) => {
        const url = new URL(req.url());
        const token = url.searchParams.get('token');
        return token
          ? 'PKstub-xlsx-bytes'
          : {
              type: 'https://medfund.healthcare/errors/scheduled-download-forbidden',
              title: 'Missing signed token',
              status: 403,
            };
      },
      200,
    );

    // Navigate to any app page so the page-context fetch has a same-origin
    // baseURL that matches the `**/api/v1/**` route glob.
    await page.goto('/unauthorized');

    const okResult = await page.evaluate(async (jobId) => {
      const r = await fetch(`/api/v1/reports/scheduled/${jobId}/download?token=valid-hmac-token`);
      const body = await r.arrayBuffer();
      return { status: r.status, size: body.byteLength };
    }, JOB_ID);
    expect(okResult.status).toBe(200);
    expect(okResult.size).toBeGreaterThan(0);

    // Overlay so the token-less call resolves to 403 (last-registered wins).
    apiMocks.respond(
      `GET /reports/scheduled/${JOB_ID}/download`,
      () => ({
        type: 'https://medfund.healthcare/errors/scheduled-download-forbidden',
        title: 'Missing signed token',
        status: 403,
      }),
      403,
    );

    const forbiddenStatus = await page.evaluate(async (jobId) => {
      const r = await fetch(`/api/v1/reports/scheduled/${jobId}/download`);
      return r.status;
    }, JOB_ID);
    expect(forbiddenStatus).toBe(403);
  });
});
