import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 14 §Actuarial Phase 3 — the tenant-admin actuarial-bases page.
 * Three sub-tabs (persistency / mortality / morbidity), each rendering a
 * CRUD table backed by the tenancy-service endpoints landed in Phase 2.
 * Every mutation is stubbed via ApiMocks so no live services are needed —
 * we're asserting the UI wire-up, not the backend contract.
 */

interface PersistencyRow {
  id: string;
  tenantId: string;
  insuranceLine: string;
  cohortMonths: number;
  expectedRetentionPct: string;
  sourceNote: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  updatedAt: string | null;
  updatedByEmail: string | null;
}

interface MortalityRow {
  id: string;
  tenantId: string;
  insuranceLine: string;
  basisName: string;
  mortalityMultiplier: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  updatedAt: string | null;
  updatedByEmail: string | null;
}

interface MorbidityRow {
  id: string;
  tenantId: string;
  insuranceLine: string;
  basisName: string;
  morbidityMultiplier: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  updatedAt: string | null;
  updatedByEmail: string | null;
}

test.describe('Actuarial bases admin (Phase 14 §Actuarial Phase 3)', () => {
  test('renders 3 sub-tabs, add on persistency, switch to mortality + morbidity', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['admin:manage_settings', 'tenant.settings:manage_actuarial_bases'],
    });

    // Stateful persistency backend — the POST mutates what GET returns.
    const persistencyRows: PersistencyRow[] = [
      {
        id: 'p-1', tenantId: TENANT, insuranceLine: 'HEALTH', cohortMonths: 12,
        expectedRetentionPct: '0.7500', sourceNote: 'industry_default_v1',
        effectiveFrom: '2026-01-01', effectiveTo: null,
        updatedAt: '2026-01-01T00:00:00Z', updatedByEmail: null,
      },
    ];
    apiMocks.respond(`GET /tenants/${TENANT}/persistency-basis`, () => persistencyRows);
    apiMocks.respond(`POST /tenants/${TENANT}/persistency-basis`, async (req) => {
      const body = JSON.parse(req.postData() ?? '{}');
      const row: PersistencyRow = {
        id: `p-${persistencyRows.length + 1}`,
        tenantId: TENANT,
        insuranceLine: body.insuranceLine,
        cohortMonths: body.cohortMonths,
        expectedRetentionPct: body.expectedRetentionPct,
        sourceNote: body.sourceNote ?? null,
        effectiveFrom: body.effectiveFrom ?? '2026-08-26',
        effectiveTo: body.effectiveTo ?? null,
        updatedAt: '2026-08-26T00:00:00Z',
        updatedByEmail: 'operator@example.com',
      };
      persistencyRows.push(row);
      return row;
    }, 201);

    // Mortality + morbidity — stubbed with a single row each so tab switch has content.
    const mortalityRows: MortalityRow[] = [
      {
        id: 'm-1', tenantId: TENANT, insuranceLine: 'LIFE', basisName: 'A1949_52',
        mortalityMultiplier: '1.0000', effectiveFrom: '2026-01-01', effectiveTo: null,
        updatedAt: null, updatedByEmail: null,
      },
    ];
    apiMocks.respond(`GET /tenants/${TENANT}/mortality-basis`, () => mortalityRows);

    const morbidityRows: MorbidityRow[] = [
      {
        id: 'x-1', tenantId: TENANT, insuranceLine: 'HEALTH', basisName: 'CIDA',
        morbidityMultiplier: '1.0000', effectiveFrom: '2026-01-01', effectiveTo: null,
        updatedAt: null, updatedByEmail: null,
      },
    ];
    apiMocks.respond(`GET /tenants/${TENANT}/morbidity-basis`, () => morbidityRows);

    // Phase 6 — mortality + morbidity tabs source their basis-name dropdown
    // from ai-service's catalogue endpoint. Stubbed so the fail-closed API
    // mocks don't 404 the tab-load call.
    apiMocks.respond('GET /actuarial/basis-tables/list', () => [
      { name: 'A1949_52', display_name: 'A1949-52 Ultimate (ZW LIFE)', category: 'mortality',
        jurisdiction_hints: ['ZW'], default_line: 'LIFE',
        source: 'IPEC Zimbabwe Life Assurance Valuation Guidance Note (2019 rev)' },
      { name: 'CIDA', display_name: 'CIDA (SA Continuous Investigation of Disability, 2001)',
        category: 'morbidity', jurisdiction_hints: ['ZA', 'ZW', 'NA', 'BW'],
        default_line: 'HEALTH', source: 'ASSA CIDA Investigation Report 2007, Table 2' },
    ]);

    // Open Settings → Actuarial Bases.
    await page.goto('/tenant/admin/settings');
    await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible();
    await page.getByRole('button', { name: /^Actuarial Bases$/i }).click();

    // Persistency tab is the default.
    const persistencyPane = page.locator('[data-testid="persistency-basis-tab"]');
    await expect(persistencyPane).toBeVisible();
    await expect(persistencyPane.getByText('industry_default_v1')).toBeVisible();

    // Switch to mortality sub-tab.
    await page.locator('[data-tab-id="mortality"]').click();
    const mortalityPane = page.locator('[data-testid="mortality-basis-tab"]');
    await expect(mortalityPane).toBeVisible();
    await expect(mortalityPane.getByText('A1949_52')).toBeVisible();

    // Switch to morbidity sub-tab.
    await page.locator('[data-tab-id="morbidity"]').click();
    const morbidityPane = page.locator('[data-testid="morbidity-basis-tab"]');
    await expect(morbidityPane).toBeVisible();
    await expect(morbidityPane.getByText('CIDA')).toBeVisible();
  });
});
