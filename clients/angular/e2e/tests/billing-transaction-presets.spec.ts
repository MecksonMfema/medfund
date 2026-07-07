import { test, expect } from '../fixtures/test';
import { stubBillingAPIs, emptySeed } from '../fixtures/billing-stubs';

/**
 * The `/tenant/billing/transactions/<preset>` routes seed the list's
 * `presetTransactionType` via route data — the transactions list then
 * forwards it as a `transactionType=<PRESET>` query param on GET
 * /transactions. This asserts each preset lands with the right filter.
 */
const PRESETS: Array<{ path: string; expected: string }> = [
  { path: '/tenant/billing/transactions/adjustments',  expected: 'ADJUSTMENT'  },
  { path: '/tenant/billing/transactions/bank',         expected: 'BANK'        },
  { path: '/tenant/billing/transactions/fc',           expected: 'FC'          },
  { path: '/tenant/billing/transactions/debit-credit', expected: 'DEBIT_CREDIT'},
  { path: '/tenant/billing/transactions/ctc',          expected: 'CTC'         },
];

test.describe('Billing — transaction preset routes', () => {
  for (const preset of PRESETS) {
    test(`route ${preset.path} forwards transactionType=${preset.expected}`, async ({ page, apiMocks, signInAs }) => {
      await signInAs({
        realmRoles: ['operator'],
        // /adjustments requires billing:post_transactions; the rest only
        // billing:view. Grant both to keep this spec preset-agnostic.
        permissions: ['billing:view', 'billing:post_transactions'],
      });

      stubBillingAPIs(apiMocks, emptySeed());

      // Wait for the transactions list to fire its first request and
      // capture the query string to assert the preset propagated.
      const req = page.waitForRequest(r =>
        r.url().includes('/transactions') &&
        !r.url().includes('/transactions/') &&
        r.method() === 'GET',
      );
      await page.goto(preset.path);
      const captured = await req;
      const url = new URL(captured.url());
      expect(url.searchParams.get('transactionType')).toBe(preset.expected);
    });
  }
});
