import type { Request } from '@playwright/test';
import { test, expect } from '../fixtures/test';

/**
 * V075 — tenant-admin Bank Accounts tab covering the CRUD surface introduced
 * by the "tenant bank accounts + stubbed gateway" plan. Two blocks:
 *
 *   1. Happy path — list → create (nominated) → edit → delete.
 *      Nominated-flip is exercised via the label rename because the tab
 *      renders one row per account and the nominated column is derived from
 *      the same seed the stubs mutate.
 *
 *   2. Permission-deny — the server rejects mutating verbs with 403 when the
 *      caller lacks {@code admin.bank_accounts:manage}. Server-side
 *      enforcement is the source of truth ({@code @RequiresPermission} on
 *      the Java controller); the tab does not hide client-side (see plan
 *      Phase 5 note — deferred).
 */

interface BankAccountRow {
  id: string;
  label: string;
  bankName: string;
  accountNumber: string;
  branchCode?: string;
  swiftCode?: string;
  accountName: string;
  currencyCode: string;
  notes?: string;
  nominated: boolean;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

interface BankSeed {
  accounts: BankAccountRow[];
}

function emptyBankSeed(): BankSeed {
  return { accounts: [] };
}

function stubBankAPIs(apiMocks: import('../fixtures/api-mocks').ApiMocks, seed: BankSeed): void {
  apiMocks.respond('GET /tenant-bank-accounts', () => seed.accounts);
  apiMocks.respond('POST /tenant-bank-accounts', async (req: Request) => {
    const body = JSON.parse(req.postData() ?? '{}') as Partial<BankAccountRow>;
    const now = new Date().toISOString();
    const created: BankAccountRow = {
      id: 'bank-e2e-' + (seed.accounts.length + 1),
      label: body.label ?? '',
      bankName: body.bankName ?? '',
      accountNumber: body.accountNumber ?? '',
      branchCode: body.branchCode,
      swiftCode: body.swiftCode,
      accountName: body.accountName ?? '',
      currencyCode: body.currencyCode ?? '',
      notes: body.notes,
      nominated: !!body.nominated,
      active: body.active !== false,
      createdAt: now,
      updatedAt: now,
    };
    // Enforce one-nominated-per-currency client-side so the stub matches the
    // server's V016 partial unique index behaviour.
    if (created.nominated) {
      for (const a of seed.accounts) {
        if (a.currencyCode === created.currencyCode) a.nominated = false;
      }
    }
    seed.accounts.push(created);
    return created;
  });
  apiMocks.respond('PUT /tenant-bank-accounts/:id', async (req: Request) => {
    const id = new URL(req.url()).pathname.split('/').pop();
    const body = JSON.parse(req.postData() ?? '{}') as Partial<BankAccountRow>;
    const idx = seed.accounts.findIndex(a => a.id === id);
    if (idx === -1) return { detail: 'not found' };
    if (body.nominated) {
      for (const a of seed.accounts) {
        if (a.currencyCode === body.currencyCode) a.nominated = false;
      }
    }
    seed.accounts[idx] = {
      ...seed.accounts[idx],
      ...body,
      updatedAt: new Date().toISOString(),
    } as BankAccountRow;
    return seed.accounts[idx];
  });
  apiMocks.respond('DELETE /tenant-bank-accounts/:id', async (req: Request) => {
    const id = new URL(req.url()).pathname.split('/').pop();
    const idx = seed.accounts.findIndex(a => a.id === id);
    if (idx > -1) seed.accounts.splice(idx, 1);
    return {};
  });
  // The bank-accounts tab's currency picker reads /currencies (activeOnly).
  apiMocks.respond('GET /currencies', () => [
    { code: 'USD', name: 'US Dollar', symbol: '$' },
    { code: 'ZWL', name: 'Zim Dollar', symbol: 'Z$' },
  ]);
}

async function openBankAccountsTab(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/tenant/admin/settings');
  // Wait for the settings shell to be interactive — tabs render eagerly but
  // the general form's async load must not race the tab click.
  await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible();
  await page.getByRole('button', { name: /^Bank Accounts$/i }).click();
  await expect(page.getByRole('heading', { name: 'Bank Accounts', exact: true })).toBeVisible();
}

test.describe('Tenant admin — bank accounts', () => {
  test('list, create nominated, edit label, delete', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['admin.bank_accounts:manage', 'admin:manage_settings'],
    });

    const seed = emptyBankSeed();
    stubBankAPIs(apiMocks, seed);

    await openBankAccountsTab(page);

    // Empty state at first — the seed is empty.
    await expect(page.getByText(/No bank accounts configured/i)).toBeVisible();

    // Open the add-account form.
    await page.getByRole('button', { name: /add account/i }).click();

    await page.locator('input[name="label"]').fill('Standard Chartered — Ops USD');
    await page.locator('input[name="bankName"]').fill('Standard Chartered');
    await page.locator('input[name="accountNumber"]').fill('0100123456');
    await page.locator('input[name="accountName"]').fill('Acme Health Fund');
    // Currency picker — open the app-select then pick USD.
    await page.locator('app-select[name="currencyCode"] .select-trigger').click();
    await page.locator('.select-option .option-label', { hasText: /^USD\b/ }).first().click();
    await page.locator('input[name="nominated"]').check();

    // Wait for the POST so we don't race the list re-fetch.
    const postResp = page.waitForResponse(
      r => r.url().endsWith('/api/v1/tenant-bank-accounts') && r.request().method() === 'POST',
    );
    await page.getByRole('button', { name: /^Create$/i }).click();
    await postResp;

    // The list refreshes; the new row is visible with the entered label.
    await expect(page.getByRole('cell', { name: /Standard Chartered — Ops USD/ })).toBeVisible();
    // Nominated chip renders in the "Nominated" column.
    await expect(page.getByText(/^Nominated$/)).toBeVisible();

    // Edit: rename the label. Row's Edit button pops the same inline form.
    await page.getByRole('button', { name: /^Edit$/i }).first().click();
    await page.locator('input[name="label"]').fill('Standard Chartered — Ops USD (renamed)');
    const putResp = page.waitForResponse(
      r => /\/api\/v1\/tenant-bank-accounts\/bank-e2e-1$/.test(r.url())
           && r.request().method() === 'PUT',
    );
    await page.getByRole('button', { name: /^Update$/i }).click();
    await putResp;

    await expect(page.getByRole('cell', { name: /renamed/ })).toBeVisible();

    // Delete: auto-accept the confirm dialog, wait for the DELETE, then the
    // empty state comes back.
    page.once('dialog', d => d.accept());
    const delResp = page.waitForResponse(
      r => /\/api\/v1\/tenant-bank-accounts\/bank-e2e-1$/.test(r.url())
           && r.request().method() === 'DELETE',
    );
    await page.getByRole('button', { name: /^Delete$/i }).first().click();
    await delResp;

    await expect(page.getByText(/No bank accounts configured/i)).toBeVisible();
  });

  test('missing admin.bank_accounts:manage → server 403 surfaces as error', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['admin:manage_settings'],
    });

    // Seed the list with one row so it renders; overlay the POST to reject
    // as the real @RequiresPermission aspect would.
    const seed: BankSeed = { accounts: [] };
    stubBankAPIs(apiMocks, seed);
    apiMocks.respond(
      'POST /tenant-bank-accounts',
      () => ({
        type: 'https://medfund.healthcare/errors/permission-denied',
        title: 'Missing required permission',
        detail: 'Missing required permission. One of: admin.bank_accounts:manage',
        status: 403,
      }),
      403,
    );

    await openBankAccountsTab(page);

    await page.getByRole('button', { name: /add account/i }).click();
    await page.locator('input[name="label"]').fill('ZWL Ops');
    await page.locator('input[name="bankName"]').fill('CBZ');
    await page.locator('input[name="accountNumber"]').fill('0200999999');
    await page.locator('input[name="accountName"]').fill('Acme Health Fund');
    await page.locator('app-select[name="currencyCode"] .select-trigger').click();
    await page.locator('.select-option .option-label', { hasText: /^ZWL\b/ }).first().click();

    const postResp = page.waitForResponse(
      r => r.url().endsWith('/api/v1/tenant-bank-accounts') && r.request().method() === 'POST',
    );
    await page.getByRole('button', { name: /^Create$/i }).click();
    const resp = await postResp;
    expect(resp.status()).toBe(403);

    // The tab's error banner surfaces the detail — no new row appears.
    await expect(page.getByText(/Missing required permission/i)).toBeVisible();
    await expect(page.getByRole('cell', { name: /ZWL Ops/ })).toHaveCount(0);
  });
});
