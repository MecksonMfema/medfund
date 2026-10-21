import { test, expect } from '../fixtures/test';

/**
 * Phase 6 of the platform-scoped-providers series: the super-admin editor for
 * the two platform junctions behind a provider.
 *
 * <p>Why it matters end to end: a provider row in `public.providers` is inert.
 * claims-service refuses a claim whose provider has no `provider_tenants` row
 * for the submitting tenant, or no `provider_insurance_lines` tag for the
 * claim's line, so this modal is the only route from "the provider exists" to
 * "the tenant can claim against it".
 */

const HEALTH_FIRST = 'aaaaaaaa-0000-4000-8000-000000000001';
const LIFE_FIRST = 'bbbbbbbb-0000-4000-8000-000000000002';
const PROVIDER = 'ccccccc1-0000-4000-8000-000000000003';

function tenantPage() {
  return {
    content: [
      { id: HEALTH_FIRST, name: 'Health First Medical', slug: 'health-first',
        status: 'active', contactEmail: 'a@b.c', countryCode: 'ZW', membershipModel: 'BOTH' },
      { id: LIFE_FIRST, name: 'Life First Assurance', slug: 'life-first',
        status: 'active', contactEmail: 'a@b.c', countryCode: 'ZA', membershipModel: 'BOTH' },
    ],
    totalCount: 2, totalPages: 1, page: 1, size: 100,
  };
}

/** Provider list page, with whatever junction state the test wants on the row. */
function providerPage(tenantIds: string[], insuranceLines: string[]) {
  return {
    content: [{
      id: PROVIDER,
      name: 'Sunrise Clinic',
      providerType: 'HEALTHCARE',
      specialty: 'GP',
      registrationNumber: 'REG-582277668',
      email: 'clinic@example.com',
      phone: '+263 77 000 0000',
      city: 'Harare',
      address: '1 Main Street',
      status: 'active',
      networkTier: 'STANDARD',
      tenantIds,
      insuranceLines,
      createdAt: '2026-09-01T00:00:00Z',
    }],
    totalCount: 1, totalPages: 1, page: 1, size: 20,
  };
}

test.describe('platform providers: tenants & lines', () => {
  test('per-row pills name the tenant and the lines', async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['super_admin'] });
    apiMocks.respond('GET /tenants', () => tenantPage());
    apiMocks.respond('GET /providers', () => providerPage([HEALTH_FIRST], ['HEALTH']));

    await page.goto('/platform/providers');

    // The payload carries a UUID; the cell must show the tenant's name.
    await expect(page.locator('td .badge.text-chip')).toHaveText(['Health First Medical']);
    // The line chip renders the data-table's friendly label, not the raw code.
    await expect(page.locator('td .badge.line-chip')).toHaveText(['Health']);
  });

  test('toggling a tenant links the provider and the chip persists on reopen',
       async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['super_admin'] });
    apiMocks.respond('GET /tenants', () => tenantPage());

    // Server-side junction state the stubs mutate, so a reopen reads back what
    // the toggle actually wrote rather than the component's local Set.
    const linked = new Set<string>([HEALTH_FIRST]);

    apiMocks.respond('GET /providers', () => providerPage([...linked], ['HEALTH']));
    apiMocks.respond(`GET /providers/:id/tenants`, () =>
      [...linked].map(tenantId => ({
        providerId: PROVIDER, tenantId, status: 'active',
        networkTier: 'STANDARD', inNetwork: true,
      })));
    apiMocks.respond(`GET /providers/:id/insurance-lines`, () => ['HEALTH']);
    apiMocks.respond(`POST /providers/:id/tenants/:tenantId`, (request) => {
      linked.add(request.url().split('/').pop()!);
      return { providerId: PROVIDER, tenantId: LIFE_FIRST, status: 'active',
               networkTier: 'STANDARD', inNetwork: true };
    }, 201);

    await page.goto('/platform/providers');
    await page.getByTestId('manage-membership').first().click();
    await expect(page.getByTestId('membership-modal')).toBeVisible();

    const lifeToggle = page.getByTestId('tenant-toggle-life-first');
    await expect(lifeToggle).toHaveAttribute('aria-pressed', 'false');
    await lifeToggle.click();
    await expect(lifeToggle).toHaveAttribute('aria-pressed', 'true');

    await page.getByTestId('membership-done').click();
    await expect(page.getByTestId('membership-modal')).toBeHidden();

    await page.getByTestId('manage-membership').first().click();
    await expect(page.getByTestId('tenant-toggle-life-first'))
      .toHaveAttribute('aria-pressed', 'true');
  });

  test('toggling a line tags the provider and the chip persists on reopen',
       async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['super_admin'] });
    apiMocks.respond('GET /tenants', () => tenantPage());

    const lines = new Set<string>(['HEALTH']);

    apiMocks.respond('GET /providers', () => providerPage([HEALTH_FIRST], [...lines]));
    apiMocks.respond(`GET /providers/:id/tenants`, () => [{
      providerId: PROVIDER, tenantId: HEALTH_FIRST, status: 'active',
      networkTier: 'STANDARD', inNetwork: true,
    }]);
    apiMocks.respond(`GET /providers/:id/insurance-lines`, () => [...lines]);
    apiMocks.respond(`POST /providers/:id/insurance-lines/:line`, (request) => {
      lines.add(request.url().split('/').pop()!);
      return '';
    }, 201);

    await page.goto('/platform/providers');
    await page.getByTestId('manage-membership').first().click();

    const travel = page.getByTestId('line-toggle-TRAVEL');
    await expect(travel).toHaveAttribute('aria-pressed', 'false');
    await travel.click();
    await expect(travel).toHaveAttribute('aria-pressed', 'true');

    await page.getByTestId('membership-done').click();
    await page.getByTestId('manage-membership').first().click();
    await expect(page.getByTestId('line-toggle-TRAVEL')).toHaveAttribute('aria-pressed', 'true');
  });

  test('a rejected link leaves the chip off and surfaces the error',
       async ({ page, apiMocks, signInAs }) => {
    await signInAs({ realmRoles: ['super_admin'] });
    apiMocks.respond('GET /tenants', () => tenantPage());
    apiMocks.respond('GET /providers', () => providerPage([HEALTH_FIRST], ['HEALTH']));
    apiMocks.respond(`GET /providers/:id/tenants`, () => [{
      providerId: PROVIDER, tenantId: HEALTH_FIRST, status: 'active',
      networkTier: 'STANDARD', inNetwork: true,
    }]);
    apiMocks.respond(`GET /providers/:id/insurance-lines`, () => ['HEALTH']);
    apiMocks.respond(`POST /providers/:id/tenants/:tenantId`, () => ({
      detail: 'Provider Sunrise Clinic is already linked to Life First Assurance',
    }), 409);

    await page.goto('/platform/providers');
    await page.getByTestId('manage-membership').first().click();
    await page.getByTestId('tenant-toggle-life-first').click();

    await expect(page.getByText('is already linked to')).toBeVisible();
    await expect(page.getByTestId('tenant-toggle-life-first'))
      .toHaveAttribute('aria-pressed', 'false');
  });
});
