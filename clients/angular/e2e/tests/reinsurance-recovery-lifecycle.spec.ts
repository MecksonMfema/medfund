import { test, expect, TENANT } from '../fixtures/test';

/**
 * Phase 10 §B (Phase 8) — Recovery lifecycle actions surfaced on the
 * recoveries bordereau page: mark-received + write-off. Uses two
 * separate scenarios to cover each action's happy path in isolation.
 */

function seededReinsurer(id: string, name: string) {
  return { id, name, active: true, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' };
}
function seededTreaty(id: string, ref: string) {
  return {
    id, treatyRef: ref, treatyType: 'QUOTA_SHARE', declaredCurrency: 'USD',
    inceptionDate: '2026-01-01', expiryDate: '2026-12-31', status: 'ACTIVE',
    renewedFromTreatyId: null,
    aggregateLimit: null, aggregateLimitCurrency: null,
    expectedAnnualPremium: null, producerRef: null,
    activatedAt: '2026-01-01T00:00:00Z',
    createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
  };
}
function reportCatalogue() {
  return [
    { id: null, tenantId: TENANT, reportKey: 'REINSURANCE_RECOVERIES',
      label: 'Recoveries bordereau', family: 'REINSURANCE', familyLabel: 'Reinsurance',
      enabled: true, description: '' },
  ];
}

test.describe('Recovery lifecycle actions (Phase 8)', () => {
  test('mark-received records a payment and reloads', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance.reinsurance:view', 'finance.reinsurance:record_recovery_received'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /reinsurance/reinsurers', () => ({
      content: [seededReinsurer('re-1', 'Munich Re')], total: 1, page: 0, size: 200, totalPages: 1,
    }));
    apiMocks.respond('GET /reinsurance/treaties', () => ({
      content: [seededTreaty('t-1', 'HEALTH-QS-2026')], total: 1, page: 0, size: 200, totalPages: 1,
    }));

    let status = 'EXPECTED';
    apiMocks.respond('GET /reports/reinsurance/recoveries-bordereau', () => ({
      reportKey: 'REINSURANCE_RECOVERIES',
      period: { periodStart: '2026-07-01', periodEnd: '2026-10-01', grain: 'QUARTERLY' },
      reportingCurrency: 'USD',
      data: [{
        recoveryId: 'rc-1', cessionId: 'ce-1', treatyId: 't-1', treatyRef: 'HEALTH-QS-2026',
        reinsurerId: 're-1', reinsurerName: 'Munich Re',
        sharePct: '60.0000', status,
        sourceEventId: 'cl-1', occurredAt: '2026-07-15T10:00:00Z',
        nativeExpected: '3000.00',
        nativeReceived: status === 'RECEIVED' ? '2500.00' : null,
        participantExpected: '1800.00',
        participantReceived: status === 'RECEIVED' ? '1500.00' : null,
        currencyCode: 'USD',
        invoicedAt: null, receivedAt: status === 'RECEIVED' ? '2026-08-22T10:00:00Z' : null,
        writeOffReason: null,
        createdAt: '2026-07-15T10:05:00Z',
        priorPeriodAdjustment: false,
      }],
      perCurrency: { USD: { totalAmount: '1800.00', rowCount: 1 } },
      fxRates: {}, warnings: [],
      generatedAt: '2026-08-22T10:00:00Z',
    }));

    let receivedPayload: unknown = null;
    apiMocks.respond('PUT /reinsurance/recoveries/rc-1/mark-received', (req) => {
      receivedPayload = req.body;
      status = 'RECEIVED';
      return {
        id: 'rc-1', cessionId: 'ce-1', status: 'RECEIVED',
        expectedAmount: 3000, receivedAmount: 2500,
        currencyCode: 'USD', invoicedAt: null, receivedAt: '2026-08-22T10:00:00Z',
        writeOffReason: null,
      };
    });

    await page.goto('/tenant/finance/reports/reinsurance/recoveries-bordereau');
    await expect(page.getByRole('heading', { name: 'Recoveries bordereau' })).toBeVisible();
    await expect(page.getByText('EXPECTED', { exact: true })).toBeVisible();

    await page.getByTestId('mark-received-btn').click();
    await expect(page.getByTestId('mark-received-modal')).toBeVisible();
    await page.getByTestId('mark-received-amount').fill('2500');

    const resp = page.waitForResponse(
      r => r.url().includes('/api/v1/reinsurance/recoveries/rc-1/mark-received')
        && r.request().method() === 'PUT',
    );
    await page.getByTestId('mark-received-submit').click();
    expect((await resp).status()).toBe(200);
    expect(receivedPayload).toMatchObject({ receivedAmount: 2500 });

    await expect(page.getByText('RECEIVED', { exact: true })).toBeVisible();
  });

  test('write-off records reason and reloads', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['operator'],
      permissions: ['finance.reinsurance:view', 'finance.reinsurance:writeoff_recovery'],
    });

    apiMocks.respond(`GET /tenants/${TENANT}/report-config`, () => reportCatalogue());
    apiMocks.respond('GET /reinsurance/reinsurers', () => ({
      content: [seededReinsurer('re-1', 'Munich Re')], total: 1, page: 0, size: 200, totalPages: 1,
    }));
    apiMocks.respond('GET /reinsurance/treaties', () => ({
      content: [seededTreaty('t-1', 'HEALTH-QS-2026')], total: 1, page: 0, size: 200, totalPages: 1,
    }));

    let status = 'INVOICED';
    apiMocks.respond('GET /reports/reinsurance/recoveries-bordereau', () => ({
      reportKey: 'REINSURANCE_RECOVERIES',
      period: { periodStart: '2026-07-01', periodEnd: '2026-10-01', grain: 'QUARTERLY' },
      reportingCurrency: 'USD',
      data: [{
        recoveryId: 'rc-1', cessionId: 'ce-1', treatyId: 't-1', treatyRef: 'HEALTH-QS-2026',
        reinsurerId: 're-1', reinsurerName: 'Munich Re',
        sharePct: '60.0000', status,
        sourceEventId: 'cl-1', occurredAt: '2026-07-15T10:00:00Z',
        nativeExpected: '3000.00', nativeReceived: null,
        participantExpected: '1800.00', participantReceived: null,
        currencyCode: 'USD',
        invoicedAt: '2026-08-01T10:00:00Z',
        receivedAt: null,
        writeOffReason: status === 'WRITTEN_OFF' ? 'reinsurer disputed' : null,
        createdAt: '2026-07-15T10:05:00Z',
        priorPeriodAdjustment: false,
      }],
      perCurrency: { USD: { totalAmount: '1800.00', rowCount: 1 } },
      fxRates: {}, warnings: [],
      generatedAt: '2026-08-22T10:00:00Z',
    }));

    let writeOffPayload: unknown = null;
    apiMocks.respond('PUT /reinsurance/recoveries/rc-1/write-off', (req) => {
      writeOffPayload = req.body;
      status = 'WRITTEN_OFF';
      return {
        id: 'rc-1', cessionId: 'ce-1', status: 'WRITTEN_OFF',
        expectedAmount: 3000, receivedAmount: null,
        currencyCode: 'USD', invoicedAt: '2026-08-01T10:00:00Z',
        receivedAt: null, writeOffReason: 'reinsurer disputed',
      };
    });

    await page.goto('/tenant/finance/reports/reinsurance/recoveries-bordereau');
    await expect(page.getByRole('heading', { name: 'Recoveries bordereau' })).toBeVisible();
    await expect(page.getByText('INVOICED', { exact: true })).toBeVisible();

    await page.getByTestId('write-off-btn').click();
    await expect(page.getByTestId('write-off-modal')).toBeVisible();
    await page.getByTestId('write-off-reason').fill('reinsurer disputed');

    const resp = page.waitForResponse(
      r => r.url().includes('/api/v1/reinsurance/recoveries/rc-1/write-off')
        && r.request().method() === 'PUT',
    );
    await page.getByTestId('write-off-submit').click();
    expect((await resp).status()).toBe(200);
    expect(writeOffPayload).toMatchObject({ reason: 'reinsurer disputed' });

    await expect(page.getByText('WRITTEN_OFF', { exact: true })).toBeVisible();
  });
});
