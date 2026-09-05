import { test, expect } from '../fixtures/test';

/**
 * Phase 17 §D.1 — public unsubscribe landing page.
 *
 * The scheduled-delivery email footer carries `/public/unsubscribe/{token}`.
 * The route is auth-free (route lives outside LayoutComponent's authGuard
 * children), and the token itself is the credential. The plan's ideal path
 * force-fires a real email and extracts the URL from mailpit; the harness
 * here has no mail server, so the spec navigates to the URL directly.
 *
 * The backend deliberately returns `success: false` (not 404) for both
 * unknown and expired tokens to prevent enumeration. The UI surfaces a
 * uniform "couldn't process" message rather than distinguishing the two.
 */

const VALID_TOKEN = '66666666-6666-6666-6666-666666666666';

test.describe('Public unsubscribe page (Phase 17 §D.1)', () => {
  test('valid token → confirm → success state; POST carries reason', async ({ page, apiMocks, signInAs }) => {
    // The route is public (no authGuard), but the SPA's APP_INITIALIZER still
    // runs keycloak.init(). Sign in so the harness gets past init; the auth
    // status is orthogonal to the unsubscribe flow being exercised. Making
    // the SPA fully skip keycloak.init() on public paths is a Phase 11
    // follow-up (real recipients are not logged in).
    await signInAs({ realmRoles: ['operator'] });

    let postedBody: unknown = null;
    apiMocks.respond(
      `POST /report-schedule-recipients/unsubscribe/:token`,
      async (req) => {
        postedBody = JSON.parse(req.postData() ?? '{}');
        return { success: true, email: 'recipient@acme.example' };
      },
    );

    await page.goto(`/public/unsubscribe/${VALID_TOKEN}`);
    await expect(page.getByRole('heading', { name: /Unsubscribe from scheduled reports/i })).toBeVisible();

    await page.locator('textarea').fill('Too many emails');

    const postReq = page.waitForRequest(
      r => r.url().includes(`/api/v1/report-schedule-recipients/unsubscribe/${VALID_TOKEN}`)
           && r.method() === 'POST',
    );
    await page.getByRole('button', { name: /^Unsubscribe me$/ }).click();
    await postReq;

    expect(postedBody).toEqual({ reason: 'Too many emails' });

    // Success card renders the recipient email.
    await expect(page.getByText(/recipient@acme\.example/)).toBeVisible();
  });

  test('malformed token → invalid_token state, no POST fired', async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['operator'] });

    let postHit = false;
    apiMocks.respond(
      `POST /report-schedule-recipients/unsubscribe/:token`,
      () => { postHit = true; return { success: true, email: null }; },
    );

    await page.goto('/public/unsubscribe/not-a-uuid');
    // Client-side validation flags a bad UUID before the user can even POST.
    await expect(page.getByText(/link is malformed/i)).toBeVisible();
    // There is no Unsubscribe button in this state.
    await expect(page.getByRole('button', { name: /^Unsubscribe me$/ })).toHaveCount(0);
    expect(postHit).toBe(false);
  });

  test('unknown/expired token → not_found state', async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['operator'] });

    apiMocks.respond(
      `POST /report-schedule-recipients/unsubscribe/:token`,
      () => ({ success: false, email: null }),
    );

    await page.goto(`/public/unsubscribe/${VALID_TOKEN}`);
    await page.getByRole('button', { name: /^Unsubscribe me$/ }).click();

    // The uniform failure message — never distinguishes unknown vs expired.
    await expect(page.getByText(/couldn.?t process this unsubscribe request/i)).toBeVisible();
  });
});
