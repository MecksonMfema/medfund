import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 15 §21 / §24 — IFRS 17 admin config surface. Golden path:
 * every sub-tab under Settings → IFRS 17 Config loads, the Phase 24
 * Market Data enrolment flow issues the expected POST end-to-end, and
 * a fresh row lands in the list after the mutation.
 *
 * The RA / yield / expense / opening-balance sub-tabs are exercised
 * primarily by the phase 2/7/19 unit tests + IT — the E2E spec here
 * proves the settings tab-shell wires up correctly and Phase 24's new
 * tab writes back through the wire the same way its peers do.
 */

interface MarketDataRow {
  id: string;
  tenantId: string;
  currency: string;
  source: 'RBZ_AUTO' | 'SARB_AUTO';
  autoFetchEnabled: boolean;
  updatedAt: string | null;
  updatedByEmail: string | null;
}

test.describe('IFRS 17 admin config CRUD (Phase 15 §21 / §24)', () => {
  test('every sub-tab loads and the Phase 24 market-data tab enrols a currency',
       async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: [
        'admin:manage_settings',
        'tenant.settings:manage_ifrs17_config',
        'underwriting:portfolio.manage',
      ],
    });

    // Sibling tabs may fetch when the settings shell mounts — stub empty.
    apiMocks.respond(`GET /tenants/${TENANT}/ifrs17-ra-config`, () => []);
    apiMocks.respond(`GET /tenants/${TENANT}/ifrs17-yield-curves`, () => []);
    apiMocks.respond(`GET /tenants/${TENANT}/ifrs17-expense-assumptions`, () => []);
    apiMocks.respond(`GET /tenants/${TENANT}/ifrs17-notification-config`, () => []);
    apiMocks.respond('GET /underwriting/portfolios', () => []);
    apiMocks.respond('GET /underwriting/opening-balances', () => []);

    // Stateful market-data catalogue — the POST mutates what GET returns.
    const marketRows: MarketDataRow[] = [];
    apiMocks.respond(`GET /tenants/${TENANT}/ifrs17-market-data-config`, () => marketRows);
    apiMocks.respond(`POST /tenants/${TENANT}/ifrs17-market-data-config`, async (req) => {
      const body = JSON.parse(req.postData() ?? '{}');
      const row: MarketDataRow = {
        id: `md-${marketRows.length + 1}`,
        tenantId: TENANT,
        currency: body.currency,
        source: body.source,
        autoFetchEnabled: body.autoFetchEnabled ?? true,
        updatedAt: '2026-08-30T00:00:00Z',
        updatedByEmail: 'operator@example.com',
      };
      marketRows.push(row);
      return row;
    }, 201);

    // Open Settings → IFRS 17 Config.
    await page.goto('/tenant/admin/settings');
    await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible();
    await page.getByRole('button', { name: /^IFRS 17 Config$/i }).click();

    // ── Every sub-tab is reachable ────────────────────────────────────────
    await expect(page.locator('[data-testid="ifrs17-ra-config-tab"]')).toBeVisible();

    await page.locator('[data-tab-id="yield-curves"]').click();
    await expect(page.locator('[data-testid="ifrs17-yield-curves-tab"]')).toBeVisible();

    await page.locator('[data-tab-id="market-data"]').click();
    const mdPane = page.locator('[data-testid="ifrs17-market-data-tab"]');
    await expect(mdPane).toBeVisible();
    await expect(mdPane.getByText(/no auto-fetch enrolments/i)).toBeVisible();

    await page.locator('[data-tab-id="expense-assumptions"]').click();
    await expect(page.locator('[data-testid="ifrs17-expense-assumptions-tab"]')).toBeVisible();

    await page.locator('[data-tab-id="opening-balances"]').click();
    await expect(page.locator('[data-testid="ifrs17-opening-balances-tab"]')).toBeVisible();

    await page.locator('[data-tab-id="notifications"]').click();
    await expect(page.locator('[data-testid="ifrs17-notifications-tab"]')).toBeVisible();

    // ── Phase 24 — enrol a currency for auto-fetch ────────────────────────
    await page.locator('[data-tab-id="market-data"]').click();
    await expect(mdPane).toBeVisible();
    // "Enrol currency" header button opens the add form.
    await mdPane.getByRole('button', { name: /^Enrol currency$/ }).first().click();
    await mdPane.locator('input[name="mdNewCurrency"]').fill('USD');

    const postReq = page.waitForRequest(
      r => r.url().includes(`/api/v1/tenants/${TENANT}/ifrs17-market-data-config`)
        && r.method() === 'POST',
    );
    // The submit button ("Enrol currency" again) is inside the form.
    await mdPane.locator('form.add-form').getByRole('button', { name: /^Enrol currency$/ }).click();
    const post = await postReq;
    const body = JSON.parse(post.postData() ?? '{}');
    expect(body.currency).toBe('USD');
    expect(body.source).toBe('RBZ_AUTO');
    expect(body.autoFetchEnabled).toBe(true);

    // Row appears in the list after the POST returns. Target the table
    // specifically — the section-sub copy and the success banner both
    // include the substring "RBZ_AUTO" / "USD".
    const dataTable = mdPane.locator('table.data-table');
    await expect(dataTable.getByText('RBZ_AUTO')).toBeVisible();
    await expect(dataTable.getByText('USD')).toBeVisible();
  });
});
