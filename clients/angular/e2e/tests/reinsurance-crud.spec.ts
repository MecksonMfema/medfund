import type { Request } from '@playwright/test';
import { test, expect } from '../fixtures/test';

/**
 * Phase 10 §A — Reinsurance CRUD happy path. Mocks the finance-service
 * reinsurance surface and drives:
 *
 *   1. Reinsurers tab: list (empty) → create → row visible.
 *   2. Treaties tab: list (empty) → new treaty page → header create →
 *      add applicable line, participant, layer → activate → status flips.
 *
 * Permission-deny variants are covered server-side by the @RequiresPermission
 * aspect and its shared IT; the client falls through if the browser hits an
 * endpoint they aren't authorized for.
 */

type UUID = string;

interface Reinsurer {
  id: UUID;
  name: string;
  contactEmail?: string;
  jurisdictionCode?: string;
  homeCurrency?: string;
  creditRating?: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

interface Treaty {
  id: UUID;
  treatyRef: string;
  treatyType: string;
  declaredCurrency: string;
  inceptionDate: string;
  expiryDate: string;
  status: string;
  renewedFromTreatyId: UUID | null;
  aggregateLimit: number | null;
  aggregateLimitCurrency: string | null;
  expectedAnnualPremium: number | null;
  producerRef: string | null;
  activatedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

interface TreatyLayer {
  id: UUID; treatyId: UUID; layerOrder: number; retention: number;
  layerLimit: number; layerCurrency: string; rate: number;
  reinstatementCount: number | null; createdAt: string;
}

interface TreatyParticipant {
  treatyId: UUID; reinsurerId: UUID; reinsurerName: string;
  sharePct: number; shareRole: 'LEADER' | 'FOLLOWING'; createdAt: string;
}

interface Line { treatyId: UUID; insuranceLine: string; }

interface Seed {
  reinsurers: Reinsurer[];
  treaties: Treaty[];
  layers: Record<UUID, TreatyLayer[]>;
  participants: Record<UUID, TreatyParticipant[]>;
  lines: Record<UUID, Line[]>;
  rules: Record<UUID, unknown[]>;
}

function emptySeed(): Seed {
  return { reinsurers: [], treaties: [], layers: {}, participants: {}, lines: {}, rules: {} };
}

function stubReinsuranceAPIs(
  apiMocks: import('../fixtures/api-mocks').ApiMocks,
  seed: Seed,
): void {
  // Paged reinsurers.
  apiMocks.respond('GET /reinsurance/reinsurers', () => ({
    content: seed.reinsurers, total: seed.reinsurers.length, page: 0, size: 50, totalPages: 1,
  }));
  apiMocks.respond('POST /reinsurance/reinsurers', async (req: Request) => {
    const body = JSON.parse(req.postData() ?? '{}') as Partial<Reinsurer>;
    const now = new Date().toISOString();
    const created: Reinsurer = {
      id: 're-e2e-' + (seed.reinsurers.length + 1),
      name: body.name ?? '',
      contactEmail: body.contactEmail,
      jurisdictionCode: body.jurisdictionCode,
      homeCurrency: body.homeCurrency,
      creditRating: body.creditRating,
      active: true, createdAt: now, updatedAt: now,
    };
    seed.reinsurers.push(created);
    return created;
  });

  // Paged treaties.
  apiMocks.respond('GET /reinsurance/treaties', () => ({
    content: seed.treaties, total: seed.treaties.length, page: 0, size: 500, totalPages: 1,
  }));
  apiMocks.respond('POST /reinsurance/treaties', async (req: Request) => {
    const body = JSON.parse(req.postData() ?? '{}') as Partial<Treaty>;
    const now = new Date().toISOString();
    const created: Treaty = {
      id: 't-e2e-' + (seed.treaties.length + 1),
      treatyRef: body.treatyRef ?? '',
      treatyType: body.treatyType ?? 'QUOTA_SHARE',
      declaredCurrency: body.declaredCurrency ?? 'USD',
      inceptionDate: body.inceptionDate ?? '',
      expiryDate: body.expiryDate ?? '',
      status: 'DRAFT',
      renewedFromTreatyId: null,
      aggregateLimit: body.aggregateLimit ?? null,
      aggregateLimitCurrency: body.aggregateLimitCurrency ?? null,
      expectedAnnualPremium: body.expectedAnnualPremium ?? null,
      producerRef: body.producerRef ?? null,
      activatedAt: null,
      createdAt: now, updatedAt: now,
    };
    seed.treaties.push(created);
    seed.layers[created.id] = [];
    seed.participants[created.id] = [];
    seed.lines[created.id] = [];
    seed.rules[created.id] = [];
    return created;
  });
  apiMocks.respond('GET /reinsurance/treaties/:id', (req: Request) => {
    const id = req.url().split('/').pop()!;
    return seed.treaties.find(t => t.id === id) ?? {};
  });
  apiMocks.respond('GET /reinsurance/treaties/:id/layers', (req: Request) => {
    const id = req.url().split('/').slice(-2, -1)[0];
    return seed.layers[id] ?? [];
  });
  apiMocks.respond('GET /reinsurance/treaties/:id/participants', (req: Request) => {
    const id = req.url().split('/').slice(-2, -1)[0];
    return seed.participants[id] ?? [];
  });
  apiMocks.respond('GET /reinsurance/treaties/:id/applicable-lines', (req: Request) => {
    const id = req.url().split('/').slice(-2, -1)[0];
    return seed.lines[id] ?? [];
  });
  apiMocks.respond('GET /reinsurance/treaties/:id/cession-rules', (req: Request) => {
    const id = req.url().split('/').slice(-2, -1)[0];
    return seed.rules[id] ?? [];
  });

  apiMocks.respond('POST /reinsurance/treaties/:id/applicable-lines', async (req: Request) => {
    const parts = new URL(req.url()).pathname.split('/');
    const treatyId = parts[parts.length - 2];
    const body = JSON.parse(req.postData() ?? '{}') as { insuranceLine: string };
    const line = { treatyId, insuranceLine: body.insuranceLine };
    seed.lines[treatyId] = [...(seed.lines[treatyId] ?? []), line];
    return line;
  });
  apiMocks.respond('PUT /reinsurance/treaties/:id/participants', async (req: Request) => {
    const parts = new URL(req.url()).pathname.split('/');
    const treatyId = parts[parts.length - 2];
    const body = JSON.parse(req.postData() ?? '{}') as Partial<TreatyParticipant>;
    const reinsurer = seed.reinsurers.find(r => r.id === body.reinsurerId);
    const now = new Date().toISOString();
    const created: TreatyParticipant = {
      treatyId,
      reinsurerId: body.reinsurerId ?? '',
      reinsurerName: reinsurer?.name ?? '',
      sharePct: body.sharePct ?? 0,
      shareRole: (body.shareRole as 'LEADER' | 'FOLLOWING') ?? 'FOLLOWING',
      createdAt: now,
    };
    seed.participants[treatyId] = [
      ...(seed.participants[treatyId] ?? []).filter(p => p.reinsurerId !== created.reinsurerId),
      created,
    ];
    return created;
  });
  apiMocks.respond('POST /reinsurance/treaties/:id/layers', async (req: Request) => {
    const parts = new URL(req.url()).pathname.split('/');
    const treatyId = parts[parts.length - 2];
    const body = JSON.parse(req.postData() ?? '{}') as Partial<TreatyLayer>;
    const now = new Date().toISOString();
    const layer: TreatyLayer = {
      id: 'l-e2e-' + ((seed.layers[treatyId] ?? []).length + 1),
      treatyId,
      layerOrder: body.layerOrder ?? 1,
      retention: body.retention ?? 0,
      layerLimit: body.layerLimit ?? 0,
      layerCurrency: body.layerCurrency ?? 'USD',
      rate: body.rate ?? 0,
      reinstatementCount: body.reinstatementCount ?? null,
      createdAt: now,
    };
    seed.layers[treatyId] = [...(seed.layers[treatyId] ?? []), layer];
    return layer;
  });
  apiMocks.respond('POST /reinsurance/treaties/:id/activate', (req: Request) => {
    const parts = new URL(req.url()).pathname.split('/');
    const treatyId = parts[parts.length - 2];
    const t = seed.treaties.find(x => x.id === treatyId);
    if (!t) return {};
    t.status = 'ACTIVE';
    t.activatedAt = new Date().toISOString();
    return t;
  });
}

test.describe('Reinsurance CRUD (§A)', () => {
  test('reinsurers tab: list empty → create → row visible', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: [
        'finance.reinsurance:view',
        'finance.reinsurance:manage_treaty',
      ],
    });
    const seed = emptySeed();
    stubReinsuranceAPIs(apiMocks, seed);

    await page.goto('/tenant/admin/reinsurance/reinsurers');
    await expect(page.getByRole('heading', { name: 'Reinsurers', exact: true })).toBeVisible();

    await page.getByRole('button', { name: /new reinsurer/i }).click();
    await page.locator('input[maxlength="200"]').first().fill('Munich Re');
    await page.locator('input[maxlength="255"]').fill('ops@munichre.example');

    const postResp = page.waitForResponse(
      r => r.url().endsWith('/api/v1/reinsurance/reinsurers') && r.request().method() === 'POST',
    );
    await page.getByRole('button', { name: /^Save$/ }).click();
    await postResp;

    await expect(page.getByText('Munich Re')).toBeVisible();
  });

  test('treaty lifecycle: create draft → add prereqs → activate flips status', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: [
        'finance.reinsurance:view',
        'finance.reinsurance:manage_treaty',
      ],
    });
    const seed = emptySeed();
    // Pre-seed a reinsurer so the participant search-select finds one.
    seed.reinsurers.push({
      id: 're-e2e-1', name: 'Munich Re', active: true,
      createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
    });
    seed.reinsurers.push({
      id: 're-e2e-2', name: 'Swiss Re', active: true,
      createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
    });
    stubReinsuranceAPIs(apiMocks, seed);

    await page.goto('/tenant/admin/reinsurance/treaties');
    await expect(page.getByRole('heading', { name: 'Treaties', exact: true })).toBeVisible();

    await page.getByRole('link', { name: /new treaty/i }).click();
    await expect(page.getByRole('heading', { name: /^New treaty$/i })).toBeVisible();

    // Header form.
    await page.locator('input[maxlength="120"]').first().fill('HEALTH-XOL-2026');
    await page.locator('select').first().selectOption('EXCESS_OF_LOSS');
    await page.locator('input[maxlength="3"][minlength="3"]').first().fill('USD');
    await page.locator('input[type="date"]').nth(0).fill('2026-01-01');
    await page.locator('input[type="date"]').nth(1).fill('2026-12-31');

    const postTreaty = page.waitForResponse(
      r => r.url().endsWith('/api/v1/reinsurance/treaties') && r.request().method() === 'POST',
    );
    await page.getByRole('button', { name: /create draft/i }).click();
    await postTreaty;

    // Applicable line: HEALTH.
    await expect(page.getByRole('heading', { name: /Applicable lines/ })).toBeVisible();
    // Line picker is the last <select> inside the Applicable lines card.
    await page.locator('section:has-text("Applicable lines") select').selectOption('HEALTH');
    const addLine = page.waitForResponse(
      r => r.url().includes('/applicable-lines') && r.request().method() === 'POST',
    );
    await page.locator('section:has-text("Applicable lines") button:has-text("Add line")').click();
    await addLine;

    // Participants — 60 / 40.
    await page.locator('input[placeholder="Search reinsurer…"]').fill('Munich');
    await page.getByText('Munich Re', { exact: true }).click();
    await page.locator('input[placeholder="Share %"]').fill('60');
    const p1 = page.waitForResponse(r => r.url().includes('/participants') && r.request().method() === 'PUT');
    await page.locator('section:has-text("Participants") button:has-text("Add / update")').click();
    await p1;

    await page.locator('input[placeholder="Search reinsurer…"]').fill('Swiss');
    await page.getByText('Swiss Re', { exact: true }).click();
    await page.locator('input[placeholder="Share %"]').fill('40');
    const p2 = page.waitForResponse(r => r.url().includes('/participants') && r.request().method() === 'PUT');
    await page.locator('section:has-text("Participants") button:has-text("Add / update")').click();
    await p2;

    // Layer.
    await expect(page.getByRole('heading', { name: /^Layers$/ })).toBeVisible();
    const layerForm = page.locator('section:has-text("Layers") .child-form.grid');
    await layerForm.locator('input[type="number"]').nth(1).fill('10000'); // retention
    await layerForm.locator('input[type="number"]').nth(2).fill('40000'); // limit
    await layerForm.locator('input[type="text"]').fill('USD'); // currency
    await layerForm.locator('input[type="number"]').nth(3).fill('0.02'); // rate
    const addLayer = page.waitForResponse(r => r.url().includes('/layers') && r.request().method() === 'POST');
    await layerForm.getByRole('button', { name: /add layer/i }).click();
    await addLayer;

    // Activate — no blockers anymore.
    page.once('dialog', d => d.accept());
    const activate = page.waitForResponse(
      r => r.url().includes('/activate') && r.request().method() === 'POST',
    );
    await page.getByRole('button', { name: /activate treaty/i }).click();
    await activate;

    await expect(page.locator('.status-badge.status-active')).toBeVisible();
  });
});
