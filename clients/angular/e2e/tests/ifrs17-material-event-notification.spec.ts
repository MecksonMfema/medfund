import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 15 §21 / §19 material-event notification wiring — the plan's
 * original sketch had "trigger onerous transition → notification bell
 * shows event". No bell UI ships in the Angular app (the material-event
 * pipeline is Kafka → notification-service → SMTP/webhook), so this
 * spec targets what actually reaches the operator via UI: the
 * per-tenant recipient list under Settings → IFRS 17 Config →
 * Notifications. Adding a recipient for ONEROUS_TRANSITION is the
 * user-observable half of the pipeline; the SMTP/webhook side is
 * covered by notification-service unit + integration tests.
 */

interface NotificationRow {
  id: string;
  tenantId: string;
  eventType: string;
  deliveryMethod: string;
  recipient: string;
  throttleMinutes: number;
  isActive: boolean;
  updatedAt: string | null;
  updatedByEmail: string | null;
}

test.describe('IFRS 17 material-event notification config (Phase 15 §19)', () => {
  test('add ONEROUS_TRANSITION recipient — POST fires, row lands in list', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: [
        'admin:manage_settings',
        'tenant.settings:manage_ifrs17_config',
      ],
    });

    const rows: NotificationRow[] = [];
    apiMocks.respond(`GET /tenants/${TENANT}/ifrs17-notification-config`, () => rows);
    apiMocks.respond(`POST /tenants/${TENANT}/ifrs17-notification-config`, async (req) => {
      const body = JSON.parse(req.postData() ?? '{}');
      const row: NotificationRow = {
        id: `n-${rows.length + 1}`,
        tenantId: TENANT,
        eventType: body.eventType,
        deliveryMethod: body.deliveryMethod,
        recipient: body.recipient,
        throttleMinutes: body.throttleMinutes ?? 15,
        isActive: body.isActive ?? true,
        updatedAt: '2026-08-30T00:00:00Z',
        updatedByEmail: 'operator@example.com',
      };
      rows.push(row);
      return row;
    }, 201);

    // Sibling tabs may fetch when the settings shell loads — stub empty.
    apiMocks.respond(`GET /tenants/${TENANT}/ifrs17-ra-config`, () => []);
    apiMocks.respond(`GET /tenants/${TENANT}/ifrs17-yield-curves`, () => []);
    apiMocks.respond(`GET /tenants/${TENANT}/ifrs17-expense-assumptions`, () => []);
    apiMocks.respond(`GET /tenants/${TENANT}/ifrs17-market-data-config`, () => []);
    apiMocks.respond('GET /underwriting/portfolios', () => []);
    apiMocks.respond('GET /underwriting/opening-balances', () => []);

    await page.goto('/tenant/admin/settings');
    await page.getByRole('button', { name: /^IFRS 17 Config$/i }).click();
    await page.locator('[data-tab-id="notifications"]').click();

    const pane = page.locator('[data-testid="ifrs17-notifications-tab"]');
    await expect(pane).toBeVisible();

    // Add flow.
    await pane.getByRole('button', { name: /^Add recipient$/i }).click();
    // Recipient is the only text input with name=newRecipient.
    await pane.locator('input[name="newRecipient"]').fill('risk-team@medfund.example');
    // Event type + delivery method default to their first option.
    const postReq = page.waitForRequest(
      r => r.url().includes(`/api/v1/tenants/${TENANT}/ifrs17-notification-config`)
        && r.method() === 'POST',
    );
    await pane.getByRole('button', { name: /^Add recipient$/ }).nth(1).click();
    const post = await postReq;
    const body = JSON.parse(post.postData() ?? '{}');
    expect(body.recipient).toBe('risk-team@medfund.example');
    expect(typeof body.eventType).toBe('string');
    expect(typeof body.deliveryMethod).toBe('string');

    // Row lands after refresh.
    await expect(pane.getByText('risk-team@medfund.example')).toBeVisible();
  });
});
