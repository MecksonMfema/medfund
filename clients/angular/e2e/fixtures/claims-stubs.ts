import type { Request } from '@playwright/test';
import { ApiMocks } from './api-mocks';

/**
 * In-memory backing store for the claims e2e stubs — a sibling to
 * {@link ../fixtures/billing-stubs.BillingSeed}. Specs seed the arrays
 * they need, POST handlers mutate them so subsequent GETs reflect the
 * writes, and every list is exposed by reference so a spec can
 * `seed.claims.push({...})` mid-flow.
 */
export interface ClaimsSeed {
  claims: Claim[];
  claimLines: Map<string, ClaimLine[]>;
  preAuths: PreAuthorization[];
  tariffSchedules: TariffSchedule[];
  tariffCodes: TariffCode[];
  tariffModifiers: TariffModifier[];
  icdCodes: IcdCode[];
  rejectionReasons: RejectionReason[];
  providers: Provider[];
  drugs: Drug[];
}

export interface Claim {
  id: string;
  claimNumber: string;
  memberId: string;
  dependantId?: string;
  providerId: string;
  schemeId: string;
  claimType: string;
  status: 'SUBMITTED' | 'VERIFIED' | 'IN_ADJUDICATION' | 'ADJUDICATED' | 'REJECTED'
       | 'PENDING_INFO' | 'COMMITTED' | 'PAID' | 'CANCELLED';
  serviceDate: string;
  submissionDate?: string;
  claimedAmount: string;
  approvedAmount?: string;
  currencyCode: string;
  diagnosisCodes?: string;
  notes?: string;
  rejectionReason?: string;
  rejectionNotes?: string;
  verificationCode?: string;
  verifiedAt?: string;
  adjudicatedAt?: string;
  adjudicatedBy?: string;
  createdAt: string;
}

export interface ClaimLine {
  id: string;
  claimId: string;
  tariffCode: string;
  description?: string;
  quantity: number;
  unitPrice: string;
  claimedAmount: string;
  approvedAmount?: string;
  modifierCodes?: string;
  currencyCode?: string;
}

export interface PreAuthorization {
  id: string;
  authNumber: string;
  memberId: string;
  providerId: string;
  schemeId?: string;
  tariffCode: string;
  diagnosisCode?: string;
  status: 'pending' | 'approved' | 'rejected' | 'expired';
  requestedAmount: string;
  approvedAmount?: string;
  currencyCode: string;
  requestedDate: string;
  decisionDate?: string;
  expiryDate?: string;
  notes?: string;
  rejectionReason?: string;
  createdAt: string;
}

export interface TariffSchedule {
  id: string;
  name: string;
  effectiveDate: string;
  endDate?: string;
  source?: string;
  status: string;
  createdAt?: string;
}

export interface TariffCode {
  id: string;
  scheduleId: string;
  code: string;
  description: string;
  category?: string;
  unitPrice: string;
  currencyCode?: string;
  requiresPreAuth?: boolean;
}

export interface TariffModifier {
  id: string;
  code: string;
  name: string;
  description?: string;
  adjustmentType: 'PERCENTAGE' | 'FIXED' | 'MULTIPLIER';
  adjustmentValue: string;
  isActive: boolean;
}

export interface IcdCode {
  id: string;
  code: string;
  description: string;
  category?: string;
  chapter?: string;
  isActive?: boolean;
}

export interface RejectionReason {
  id: string;
  code: string;
  description: string;
  category?: string;
  isActive: boolean;
}

export interface Provider {
  id: string;
  name: string;
  specialty?: string;
  registrationNumber?: string;
  status?: string;
}

export interface Drug {
  id: string;
  drugName: string;
  drugType: 'ACUTE' | 'CHRONIC' | 'OFF_LIMIT';
  tariffCode?: string;
  isActive: boolean;
}

export function emptyClaimsSeed(): ClaimsSeed {
  return {
    claims: [],
    claimLines: new Map(),
    preAuths: [],
    tariffSchedules: [],
    tariffCodes: [],
    tariffModifiers: [],
    icdCodes: [],
    rejectionReasons: [],
    providers: [],
    drugs: [],
  };
}

let _idCounter = 0;
function nextId(prefix = 'id'): string {
  _idCounter += 1;
  const n = _idCounter.toString(16).padStart(12, '0');
  return `${prefix.padStart(8, '0').slice(-8)}-e2e0-4000-8000-${n}`;
}

function jsonBody(req: Request): any {
  const raw = req.postData();
  if (!raw) return {};
  try { return JSON.parse(raw); } catch { return {}; }
}

function pathParts(req: Request): string[] {
  return new URL(req.url()).pathname.replace(/^.*\/api\/v1/, '').split('/').filter(Boolean);
}

function springPage<T>(rows: T[], page = 0, size = 20) {
  return {
    content: rows.slice(page * size, page * size + size),
    total: rows.length,
    totalElements: rows.length,
    page,
    size,
    totalPages: Math.max(1, Math.ceil(rows.length / size)),
    number: page,
  };
}

/**
 * Wire the full claims surface against an in-memory {@link ClaimsSeed}.
 * Register AFTER the harness defaults so spec-level overrides win.
 */
export function stubClaimsAPIs(apiMocks: ApiMocks, seed: ClaimsSeed): void {
  // ── Claims ─────────────────────────────────────────────────────────
  // :id first so newest-first iteration doesn't let /claims/status/... etc.
  // fall through — same lesson as billing-stubs (see history there).
  apiMocks.respond('GET /claims', () => seed.claims);
  apiMocks.respond('GET /claims/:id', (req: Request) => {
    const id = pathParts(req).pop();
    return seed.claims.find(c => c.id === id) ?? { id };
  });
  apiMocks.respond('GET /claims/status/:status', (req: Request) => {
    const status = pathParts(req).pop();
    return seed.claims.filter(c => c.status === status);
  });
  apiMocks.respond('GET /claims/member/:memberId', (req: Request) => {
    const memberId = pathParts(req).pop();
    return seed.claims.filter(c => c.memberId === memberId);
  });
  apiMocks.respond('GET /claims/provider/:providerId', (req: Request) => {
    const providerId = pathParts(req).pop();
    return seed.claims.filter(c => c.providerId === providerId);
  });
  apiMocks.respond('GET /claims/:id/lines', (req: Request) => {
    const claimId = pathParts(req)[1];
    return seed.claimLines.get(claimId) ?? [];
  });
  apiMocks.respond('POST /claims', (req: Request) => {
    const body = jsonBody(req);
    const claim: Claim = {
      id: nextId('clm'),
      claimNumber: `CLM-${(seed.claims.length + 1).toString().padStart(6, '0')}`,
      memberId: body.memberId,
      dependantId: body.dependantId,
      providerId: body.providerId,
      schemeId: body.schemeId,
      claimType: body.claimType ?? 'MEDICAL',
      status: 'SUBMITTED',
      serviceDate: body.serviceDate,
      submissionDate: new Date().toISOString().slice(0, 10),
      claimedAmount: String(body.claimedAmount ?? '0'),
      currencyCode: body.currencyCode,
      diagnosisCodes: body.diagnosisCodes,
      notes: body.notes,
      createdAt: new Date().toISOString(),
    };
    seed.claims.push(claim);
    if (Array.isArray(body.lines) && body.lines.length > 0) {
      const lines: ClaimLine[] = body.lines.map((l: any, i: number) => ({
        id: nextId('lin'),
        claimId: claim.id,
        tariffCode: l.tariffCode,
        description: l.description,
        quantity: Number(l.quantity ?? 1),
        unitPrice: String(l.unitPrice ?? '0'),
        claimedAmount: String(l.claimedAmount ?? '0'),
        modifierCodes: l.modifierCodes,
        currencyCode: l.currencyCode,
      }));
      seed.claimLines.set(claim.id, lines);
    }
    return claim;
  });
  apiMocks.respond('POST /claims/:id/verify', (req: Request) => {
    const id = pathParts(req)[1];
    const claim = seed.claims.find(c => c.id === id);
    const code = new URL(req.url()).searchParams.get('verificationCode') ?? '';
    if (claim) {
      claim.status = 'VERIFIED';
      claim.verificationCode = code;
      claim.verifiedAt = new Date().toISOString();
    }
    return claim ?? { id, status: 'VERIFIED' };
  });
  apiMocks.respond('POST /claims/:id/adjudicate', (req: Request) => {
    const id = pathParts(req)[1];
    const claim = seed.claims.find(c => c.id === id);
    if (claim) {
      claim.status = 'ADJUDICATED';
      claim.approvedAmount = claim.approvedAmount ?? claim.claimedAmount;
      claim.adjudicatedAt = new Date().toISOString();
    }
    return claim ?? { id, status: 'ADJUDICATED' };
  });
  apiMocks.respond('POST /claims/:id/status', (req: Request) => {
    const id = pathParts(req)[1];
    const claim = seed.claims.find(c => c.id === id);
    const newStatus = new URL(req.url()).searchParams.get('newStatus') as Claim['status'] | null;
    if (claim && newStatus) claim.status = newStatus;
    return claim ?? { id };
  });

  // ── Pre-authorizations ────────────────────────────────────────────
  apiMocks.respond('GET /pre-authorizations', () => seed.preAuths);
  apiMocks.respond('GET /pre-authorizations/:id', (req: Request) => {
    const id = pathParts(req).pop();
    return seed.preAuths.find(p => p.id === id) ?? { id };
  });
  apiMocks.respond('GET /pre-authorizations/member/:memberId', (req: Request) => {
    const memberId = pathParts(req).pop();
    return seed.preAuths.filter(p => p.memberId === memberId);
  });
  apiMocks.respond('GET /pre-authorizations/number/:authNumber', (req: Request) => {
    const authNumber = pathParts(req).pop();
    return seed.preAuths.find(p => p.authNumber === authNumber) ?? { authNumber };
  });
  apiMocks.respond('POST /pre-authorizations', (req: Request) => {
    const body = jsonBody(req);
    const p: PreAuthorization = {
      id: nextId('pa'),
      authNumber: `PA-${(seed.preAuths.length + 1).toString().padStart(6, '0')}`,
      memberId: body.memberId,
      providerId: body.providerId,
      schemeId: body.schemeId,
      tariffCode: body.tariffCode,
      diagnosisCode: body.diagnosisCode,
      status: 'pending',
      requestedAmount: String(body.requestedAmount ?? '0'),
      currencyCode: body.currencyCode,
      requestedDate: new Date().toISOString().slice(0, 10),
      notes: body.notes,
      createdAt: new Date().toISOString(),
    };
    seed.preAuths.push(p);
    return p;
  });
  apiMocks.respond('POST /pre-authorizations/:id/approve', (req: Request) => {
    const id = pathParts(req)[1];
    const url = new URL(req.url());
    const p = seed.preAuths.find(x => x.id === id);
    if (p) {
      p.status = 'approved';
      p.approvedAmount = url.searchParams.get('approvedAmount') ?? p.requestedAmount;
      p.expiryDate = url.searchParams.get('expiryDate') ?? undefined;
      p.decisionDate = new Date().toISOString().slice(0, 10);
    }
    return p ?? { id, status: 'approved' };
  });
  apiMocks.respond('POST /pre-authorizations/:id/reject', (req: Request) => {
    const id = pathParts(req)[1];
    const url = new URL(req.url());
    const p = seed.preAuths.find(x => x.id === id);
    if (p) {
      p.status = 'rejected';
      p.rejectionReason = url.searchParams.get('rejectionReason') ?? undefined;
      p.decisionDate = new Date().toISOString().slice(0, 10);
    }
    return p ?? { id, status: 'rejected' };
  });

  // ── Tariff schedules + codes ──────────────────────────────────────
  apiMocks.respond('GET /tariffs/schedules', () => seed.tariffSchedules);
  apiMocks.respond('GET /tariffs/schedules/:id', (req: Request) => {
    const id = pathParts(req).pop();
    return seed.tariffSchedules.find(s => s.id === id) ?? { id, name: 'Unknown' };
  });
  apiMocks.respond('POST /tariffs/schedules', (req: Request) => {
    const body = jsonBody(req);
    const s: TariffSchedule = {
      id: nextId('tsc'),
      name: body.name,
      effectiveDate: body.effectiveDate,
      endDate: body.endDate,
      source: body.source,
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
    };
    seed.tariffSchedules.push(s);
    return s;
  });
  apiMocks.respond('GET /tariffs/codes/schedule/:scheduleId', (req: Request) => {
    const scheduleId = pathParts(req).pop();
    return seed.tariffCodes.filter(c => c.scheduleId === scheduleId);
  });
  apiMocks.respond('GET /tariffs/codes/search', (req: Request) => {
    const q = new URL(req.url()).searchParams.get('q')?.toLowerCase() ?? '';
    return seed.tariffCodes.filter(c =>
      c.code.toLowerCase().includes(q) ||
      c.description.toLowerCase().includes(q));
  });
  apiMocks.respond('POST /tariffs/codes', (req: Request) => {
    const body = jsonBody(req);
    const c: TariffCode = {
      id: nextId('tc'),
      scheduleId: body.scheduleId,
      code: body.code,
      description: body.description,
      category: body.category,
      unitPrice: String(body.unitPrice ?? '0'),
      currencyCode: body.currencyCode,
      requiresPreAuth: body.requiresPreAuth ?? false,
    };
    seed.tariffCodes.push(c);
    return c;
  });
  apiMocks.respond('GET /tariffs/modifiers', () => seed.tariffModifiers);

  // ── ICD-10 ────────────────────────────────────────────────────────
  apiMocks.respond('GET /icd-codes/search', (req: Request) => {
    const q = new URL(req.url()).searchParams.get('q')?.toLowerCase() ?? '';
    return seed.icdCodes.filter(c =>
      c.code.toLowerCase().includes(q) ||
      c.description.toLowerCase().includes(q));
  });

  // ── Rejection reasons ─────────────────────────────────────────────
  apiMocks.respond('GET /rejection-reasons', (req: Request) => {
    const activeOnly = new URL(req.url()).searchParams.get('activeOnly') === 'true';
    return activeOnly ? seed.rejectionReasons.filter(r => r.isActive) : seed.rejectionReasons;
  });
  apiMocks.respond('POST /rejection-reasons', (req: Request) => {
    const body = jsonBody(req);
    const r: RejectionReason = {
      id: nextId('rr'),
      code: body.code,
      description: body.description,
      category: body.category,
      isActive: body.isActive ?? true,
    };
    seed.rejectionReasons.push(r);
    return r;
  });
  apiMocks.respond('PUT /rejection-reasons/:id', (req: Request) => {
    const id = pathParts(req).pop();
    const body = jsonBody(req);
    const r = seed.rejectionReasons.find(x => x.id === id);
    if (r) Object.assign(r, body);
    return r ?? { id, ...body };
  });
  apiMocks.respond('DELETE /rejection-reasons/:id', () => ({}));

  // ── Providers (Spring-paginated) ──────────────────────────────────
  apiMocks.respond('GET /providers', (req: Request) => {
    const url = new URL(req.url());
    const q = url.searchParams.get('q')?.toLowerCase() ?? '';
    const size = +(url.searchParams.get('size') ?? 20);
    const page = +(url.searchParams.get('page') ?? 0);
    const rows = q
      ? seed.providers.filter(p => p.name.toLowerCase().includes(q))
      : seed.providers;
    return springPage(rows, page, size);
  });

  // ── Drugs ─────────────────────────────────────────────────────────
  apiMocks.respond('GET /drugs', (req: Request) => {
    const activeOnly = new URL(req.url()).searchParams.get('activeOnly') === 'true';
    return activeOnly ? seed.drugs.filter(d => d.isActive) : seed.drugs;
  });
  apiMocks.respond('GET /drugs/:id', (req: Request) => {
    const id = pathParts(req).pop();
    return seed.drugs.find(d => d.id === id) ?? { id };
  });
  apiMocks.respond('POST /drugs', (req: Request) => {
    const body = jsonBody(req);
    const d: Drug = {
      id: nextId('drg'),
      drugName: body.drugName,
      drugType: body.drugType ?? 'ACUTE',
      tariffCode: body.tariffCode,
      isActive: body.isActive ?? true,
    };
    seed.drugs.push(d);
    return d;
  });
  apiMocks.respond('PUT /drugs/:id', (req: Request) => {
    const id = pathParts(req).pop();
    const body = jsonBody(req);
    const d = seed.drugs.find(x => x.id === id);
    if (d) Object.assign(d, body);
    return d ?? { id, ...body };
  });

  // ── Tenant-stats helpers claims dashboard likely hits ─────────────
  // (Match the shape used by dashboard-tab-gating.spec.ts.)
  apiMocks.respond('GET /tenant-stats/claims-status-distribution', () => []);
  apiMocks.respond('GET /tenant-stats/recent-claims', () => []);
}
