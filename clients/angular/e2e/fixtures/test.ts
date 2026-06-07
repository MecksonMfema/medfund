import { test as base, expect } from '@playwright/test';
import { AuthIdentity, stubKeycloak } from './auth';
import { ApiMocks, defaults as apiDefaults } from './api-mocks';

interface Fixtures {
  /** Pre-installed API mock router. Tests override defaults via `apiMocks.respond(...)`. */
  apiMocks: ApiMocks;
  /** Sign in as a tenant operator with the supplied permissions. */
  signInAs: (identity: AuthIdentity & { permissions?: string[] }) => Promise<void>;
}

/** Per-test tenant id — kept stable so route stubs can hard-code it. */
const TENANT_ID = '11111111-1111-1111-1111-111111111111';

export const test = base.extend<Fixtures>({
  apiMocks: async ({ page }, use) => {
    const mocks = new ApiMocks(page);
    await mocks.install();
    await use(mocks);
  },

  signInAs: async ({ page, apiMocks }, use) => {
    await use(async (identity) => {
      const permissions = identity.permissions ?? [];
      apiDefaults(apiMocks, {
        tenantId: identity.tenantId ?? TENANT_ID,
        permissions,
      });
      await stubKeycloak(page, {
        ...identity,
        tenantId: identity.tenantId ?? TENANT_ID,
      });
    });
  },
});

export { expect };
export const TENANT = TENANT_ID;
