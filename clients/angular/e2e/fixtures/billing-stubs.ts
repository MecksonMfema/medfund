import type { Request } from '@playwright/test';
import { ApiMocks } from './api-mocks';

/**
 * In-memory backing store for the billing e2e stubs. Individual test files
 * seed this with the fixtures they need; POST handlers mutate it so
 * subsequent GETs reflect the writes — same illusion as a real backend
 * without touching one. Every list is exposed by reference so a spec can
 * `seed.invoices.push({...})` at any point mid-flow.
 */
export interface BillingSeed {
  groups: BillingGroup[];
  schemes: BillingScheme[];
  members: BillingMember[];
  dependants: BillingDependant[];
  invoices: BillingInvoice[];
  transactions: BillingTransaction[];
  badDebts: CreditorRow[];
  jobRuns: JobRun[];
  groupChanges: GroupChange[];
  schemeChanges: SchemeChange[];
  swaps: MemberSwap[];
  chargePreview: ChargePreview | null;
  billingPreview: BillingPreview | null;
  statement: Statement | null;
}

export interface BillingGroup {
  id: string;
  name: string;
  registrationNumber: string;
  email?: string;
  address?: string;
  liaisonKind?: 'MEMBER' | 'STAFF' | 'LIAISON' | null;
  liaisonUserId?: string | null;
  status: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface BillingScheme {
  id: string;
  name: string;
  schemeType: string;
  status: string;
  effectiveDate: string;
  endDate: string | null;
  currencyCode: string;
  description: string | null;
  insuranceLine?: string;
}

export interface BillingMember {
  id: string;
  memberNumber: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  email: string;
  phone: string;
  status: string;
  groupId: string | null;
  schemeId: string | null;
  enrollmentDate: string;
  createdAt: string;
}

export interface BillingDependant {
  id: string;
  memberId: string;
  firstName: string;
  lastName: string;
  dateOfBirth?: string;
  gender?: string;
  relationship: string;
  status: string;
  enrollmentDate?: string;
}

export interface BillingInvoice {
  id: string;
  invoiceNumber: string;
  status: string;
  holderType: 'GROUP' | 'INDIVIDUAL';
  holderId: string;
  holderName: string;
  targetCode?: string;
  periodStart: string;
  periodEnd: string;
  currencyCode: string;
  insuranceLine?: string;
  totalAmount: number;
  amountDue: number;
  committedAt?: string;
  issuedAt?: string;
}

export interface BillingTransaction {
  id: string;
  amount: number;
  currencyCode: string;
  transactionTypeCode: string;
  paymentMethodCode?: string;
  reference?: string;
  reason?: string;
  targetType?: string;
  memberId?: string | null;
  groupId?: string | null;
  contributionId?: string | null;
  invoiceId?: string | null;
  createdAt: string;
}

export interface CreditorRow {
  subjectType: 'GROUP' | 'INDIVIDUAL';
  subjectId: string;
  subjectName: string;
  currencyCode: string;
  outstandingBalance: number;
  daysSinceLastActivity: number;
}

export interface JobRun {
  id: string;
  configId: string;
  tenantId: string | null;
  startedAt: string;
  endedAt: string | null;
  durationMs: number | null;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED';
  triggerKind: 'schedule' | 'manual';
  errorMessage: string | null;
  triggeredBy: string | null;
  resultPayload: string | null;
  kind?: 'preview' | 'commit';
}

export interface GroupChange {
  id: string;
  memberId: string;
  fromGroupId: string | null;
  toGroupId: string;
  status: 'PENDING' | 'APPLIED' | 'REJECTED';
  requestedDate: string;
  effectiveDate: string;
  reason?: string | null;
  backdated: boolean;
  appliedAt?: string | null;
}

export interface SchemeChange {
  id: string;
  memberId: string;
  fromSchemeId: string | null;
  toSchemeId: string;
  status: 'PENDING' | 'APPROVED' | 'EFFECTIVE' | 'REJECTED' | 'CANCELLED';
  requestedDate: string;
  effectiveDate: string;
  reason?: string | null;
  waitingPeriodDays?: number;
  changeKind?: string | null;
  appliedAt?: string | null;
}

export interface MemberSwap {
  id: string;
  oldMemberId: string;
  dependantId: string;
  newMemberId: string | null;
  oldDependantId: string | null;
  status: 'PENDING' | 'APPLIED' | 'REJECTED';
  requestedDate: string;
  effectiveDate: string;
  reason?: string | null;
  appliedAt?: string | null;
}

export interface ChargePreview {
  subjectType: 'GROUP' | 'MEMBER';
  subjectId: string;
  subjectName: string;
  asOf: string;
  periodStart: string;
  periodEnd: string;
  excludedTerminating: number;
  lines: ChargePreviewLine[];
  totals: Record<string, number>;
}

export interface ChargePreviewLine {
  personName: string;
  personType: 'MEMBER' | 'DEPENDANT';
  memberNumber: string;
  schemeName: string;
  ageBandName?: string;
  amount: number;
  currencyCode: string;
  isCustomPriced?: boolean;
  scheduledSchemeChangeFrom?: string | null;
}

export interface BillingPreview {
  totalRows: number;
  groupInvoicesProjected: number;
  individualInvoicesProjected: number;
  totalsByCurrency: Record<string, number>;
  sample: Array<{
    routingKey: string;
    holderName: string;
    holderType: 'GROUP' | 'INDIVIDUAL';
    currencyCode: string;
    personName: string;
    personType: 'MEMBER' | 'DEPENDANT';
    memberNumber: string;
    schemeName: string;
    ageBand: string | null;
    amount: number;
  }>;
  cooldownActive: boolean;
  cooldownRemainingMinutes: number;
  membershipModel: string;
}

export interface Statement {
  header: {
    targetType: 'GROUP' | 'INDIVIDUAL';
    targetId: string;
    targetName: string;
    targetCode?: string;
    periodStart: string;
    periodEnd: string;
    currencyCode: string;
  };
  lines: Array<{
    date: string;
    description: string;
    reference?: string;
    debit?: number;
    credit?: number;
    runningBalance: number;
    type?: 'OPENING_BALANCE' | 'CONTRIBUTION' | 'PAYMENT' | 'CLOSING_BALANCE';
  }>;
}

/**
 * Zero-value seed. Specs mutate this before / during the flow rather than
 * bespoke seeding each time.
 */
export function emptySeed(): BillingSeed {
  return {
    groups: [],
    schemes: [],
    members: [],
    dependants: [],
    invoices: [],
    transactions: [],
    badDebts: [],
    jobRuns: [],
    groupChanges: [],
    schemeChanges: [],
    swaps: [],
    chargePreview: null,
    billingPreview: null,
    statement: null,
  };
}

/** Deterministic UUID-ish generator so spec assertions can hard-code IDs. */
let counter = 0;
export function nextId(prefix = 'id'): string {
  counter += 1;
  const n = counter.toString(16).padStart(12, '0');
  return `${prefix.padStart(8, '0').slice(-8)}-e2e0-4000-8000-${n}`;
}

function jsonBody(req: Request): any {
  const raw = req.postData();
  if (!raw) return {};
  try { return JSON.parse(raw); } catch { return {}; }
}

function pathParts(req: Request): string[] {
  const url = new URL(req.url());
  return url.pathname.replace(/^.*\/api\/v1/, '').split('/').filter(Boolean);
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

function cursorPage<T>(rows: T[]) {
  return { items: rows, nextCursor: null };
}

/**
 * Wire the full billing API surface against an in-memory {@link BillingSeed}.
 * Register AFTER the harness defaults so spec-level overrides win.
 */
export function stubBillingAPIs(apiMocks: ApiMocks, seed: BillingSeed): void {
  // ── Groups ─────────────────────────────────────────────────────────
  // Iteration is newest-first (see api-mocks.ts) so `:id` stubs must be
  // registered BEFORE more-specific ones like `/search`, otherwise the
  // generic pattern grabs the specific URL and silently returns junk.
  apiMocks.respond('GET /groups', () => seed.groups);
  apiMocks.respond('GET /groups/:id', (req: Request) => {
    const id = pathParts(req).pop();
    return seed.groups.find(g => g.id === id) ?? { id, name: 'Unknown' };
  });
  apiMocks.respond('GET /groups/search', (req: Request) => {
    const q = new URL(req.url()).searchParams.get('q')?.toLowerCase() ?? '';
    return seed.groups.filter(g => g.name.toLowerCase().includes(q));
  });
  apiMocks.respond('GET /billing/groups/search', (req: Request) => {
    const q = new URL(req.url()).searchParams.get('q')?.toLowerCase() ?? '';
    return seed.groups
      .filter(g => g.name.toLowerCase().includes(q))
      .map(g => ({ id: g.id, name: g.name, registrationNumber: g.registrationNumber }));
  });
  apiMocks.respond('POST /groups', (req: Request) => {
    const body = jsonBody(req);
    const g: BillingGroup = {
      id: nextId('grp'),
      name: body.name,
      registrationNumber: body.registrationNumber ?? `REG-${seed.groups.length + 1}`,
      email: body.email,
      address: body.address,
      liaisonKind: body.liaisonKind ?? null,
      liaisonUserId: body.liaisonUserId ?? null,
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
    };
    seed.groups.push(g);
    return g;
  });
  apiMocks.respond('PUT /groups/:id', (req: Request) => {
    const id = pathParts(req).pop();
    const body = jsonBody(req);
    const g = seed.groups.find(x => x.id === id);
    if (!g) return { id, ...body };
    Object.assign(g, body);
    return g;
  });
  apiMocks.respond('POST /groups/:id/suspend', (req: Request) => {
    const id = pathParts(req)[1];
    const g = seed.groups.find(x => x.id === id);
    if (g) g.status = 'SUSPENDED';
    return g ?? { id, status: 'SUSPENDED' };
  });
  apiMocks.respond('POST /groups/:id/actions/terminate', (req: Request) => {
    const id = pathParts(req)[1];
    const g = seed.groups.find(x => x.id === id);
    if (g) g.status = 'TERMINATED';
    return g ?? { id, status: 'TERMINATED' };
  });

  // ── Schemes (register :id BEFORE /page and /search so newest-first
  // iteration picks the specific paths). ─────────────────────────────
  apiMocks.respond('GET /schemes', () => seed.schemes);
  apiMocks.respond('GET /schemes/:id', (req: Request) => {
    const id = pathParts(req).pop();
    return seed.schemes.find(s => s.id === id) ?? { id, name: 'Unknown', currencyCode: 'USD', status: 'active' };
  });
  apiMocks.respond('GET /schemes/page', (req: Request) => {
    const url = new URL(req.url());
    return springPage(seed.schemes, +(url.searchParams.get('page') ?? 0), +(url.searchParams.get('size') ?? 20));
  });
  apiMocks.respond('GET /schemes/search', (req: Request) => {
    const q = new URL(req.url()).searchParams.get('q')?.toLowerCase() ?? '';
    return seed.schemes.filter(s => s.name.toLowerCase().includes(q));
  });
  apiMocks.respond('POST /schemes', (req: Request) => {
    const body = jsonBody(req);
    const s: BillingScheme = {
      id: nextId('sch'),
      name: body.name,
      schemeType: body.schemeType ?? 'medical_aid',
      status: 'active',
      effectiveDate: body.effectiveDate,
      endDate: body.endDate ?? null,
      currencyCode: body.currencyCode,
      description: body.description ?? null,
      insuranceLine: body.insuranceLine ?? 'HEALTH',
    };
    seed.schemes.push(s);
    return s;
  });

  // ── Members (register :id BEFORE /search + /group/:groupId) ──────
  apiMocks.respond('GET /members', (req: Request) => {
    const q = new URL(req.url()).searchParams.get('q')?.toLowerCase() ?? '';
    const rows = q
      ? seed.members.filter(m =>
          `${m.firstName} ${m.lastName}`.toLowerCase().includes(q) ||
          m.memberNumber.toLowerCase().includes(q))
      : seed.members;
    return cursorPage(rows);
  });
  apiMocks.respond('GET /members/:id', (req: Request) => {
    const id = pathParts(req).pop();
    return seed.members.find(m => m.id === id) ?? { id };
  });
  apiMocks.respond('GET /members/search', (req: Request) => {
    const q = new URL(req.url()).searchParams.get('q')?.toLowerCase() ?? '';
    return seed.members.filter(m =>
      `${m.firstName} ${m.lastName}`.toLowerCase().includes(q));
  });
  apiMocks.respond('GET /members/group/:groupId', (req: Request) => {
    const parts = pathParts(req);
    const groupId = parts[parts.length - 1];
    return seed.members.filter(m => m.groupId === groupId);
  });
  apiMocks.respond('POST /members', (req: Request) => {
    const body = jsonBody(req);
    const m: BillingMember = {
      id: nextId('mem'),
      memberNumber: body.memberNumber ?? `M${(seed.members.length + 1).toString().padStart(6, '0')}`,
      firstName: body.firstName,
      lastName: body.lastName,
      dateOfBirth: body.dateOfBirth,
      email: body.email ?? '',
      phone: body.phone ?? '',
      status: 'ACTIVE',
      groupId: body.groupId ?? null,
      schemeId: body.schemeId ?? null,
      enrollmentDate: body.enrollmentDate ?? new Date().toISOString().slice(0, 10),
      createdAt: new Date().toISOString(),
    };
    seed.members.push(m);
    return m;
  });
  apiMocks.respond('PUT /members/:id', (req: Request) => {
    const id = pathParts(req).pop();
    const body = jsonBody(req);
    const m = seed.members.find(x => x.id === id);
    if (m) Object.assign(m, body);
    return m ?? { id, ...body };
  });
  apiMocks.respond('POST /members/:id/activate', (req: Request) => transitionMember(seed, pathParts(req)[1], 'ACTIVE'));
  apiMocks.respond('POST /members/:id/suspend',  (req: Request) => transitionMember(seed, pathParts(req)[1], 'SUSPENDED'));
  apiMocks.respond('POST /members/:id/terminate',(req: Request) => transitionMember(seed, pathParts(req)[1], 'TERMINATED'));

  // ── Dependants ─────────────────────────────────────────────────────
  apiMocks.respond('GET /dependants/member/:memberId', (req: Request) => {
    const memberId = pathParts(req).pop();
    return seed.dependants.filter(d => d.memberId === memberId);
  });
  apiMocks.respond('POST /dependants', (req: Request) => {
    const body = jsonBody(req);
    const d: BillingDependant = {
      id: nextId('dep'),
      memberId: body.memberId,
      firstName: body.firstName,
      lastName: body.lastName,
      dateOfBirth: body.dateOfBirth,
      gender: body.gender,
      relationship: body.relationship,
      status: 'ACTIVE',
      enrollmentDate: body.enrollmentDate,
    };
    seed.dependants.push(d);
    return d;
  });
  apiMocks.respond('PUT /dependants/:id', (req: Request) => {
    const id = pathParts(req).pop();
    const body = jsonBody(req);
    const d = seed.dependants.find(x => x.id === id);
    if (d) Object.assign(d, body);
    return d ?? { id, ...body };
  });
  apiMocks.respond('POST /dependants/:id/deactivate', (req: Request) => {
    const id = pathParts(req)[1];
    const body = jsonBody(req);
    const d = seed.dependants.find(x => x.id === id);
    if (d) {
      d.status = 'DEACTIVATED';
      if (body.effectiveDate) d.enrollmentDate = body.enrollmentDate;
    }
    return d ?? { id, status: 'DEACTIVATED' };
  });

  // ── Group changes ──────────────────────────────────────────────────
  apiMocks.respond('POST /members/:id/group-changes', (req: Request) => {
    const memberId = pathParts(req)[1];
    const body = jsonBody(req);
    const member = seed.members.find(m => m.id === memberId);
    const today = new Date().toISOString().slice(0, 10);
    const gc: GroupChange = {
      id: nextId('gch'),
      memberId,
      fromGroupId: member?.groupId ?? null,
      toGroupId: body.targetGroupId,
      status: body.effectiveDate < today ? 'APPLIED' : 'PENDING',
      requestedDate: today,
      effectiveDate: body.effectiveDate,
      reason: body.reason ?? null,
      backdated: body.effectiveDate < today,
      appliedAt: body.effectiveDate < today ? new Date().toISOString() : null,
    };
    if (gc.status === 'APPLIED' && member) member.groupId = body.targetGroupId;
    seed.groupChanges.push(gc);
    return gc;
  });
  apiMocks.respond('GET /members/:id/group-changes', (req: Request) => {
    const memberId = pathParts(req)[1];
    return seed.groupChanges.filter(gc => gc.memberId === memberId);
  });
  apiMocks.respond('POST /members/:id/group-changes/:changeId/approve', (req: Request) => {
    const parts = pathParts(req);
    const memberId = parts[1];
    const changeId = parts[3];
    const gc = seed.groupChanges.find(x => x.id === changeId);
    if (gc) {
      gc.status = 'APPLIED';
      gc.appliedAt = new Date().toISOString();
      const m = seed.members.find(x => x.id === memberId);
      if (m) m.groupId = gc.toGroupId;
    }
    return gc ?? { id: changeId, status: 'APPLIED' };
  });
  apiMocks.respond('POST /members/:id/group-changes/:changeId/reject', (req: Request) => {
    const changeId = pathParts(req)[3];
    const gc = seed.groupChanges.find(x => x.id === changeId);
    if (gc) gc.status = 'REJECTED';
    return gc ?? { id: changeId, status: 'REJECTED' };
  });

  // ── Scheme changes ─────────────────────────────────────────────────
  // Matches the real contributions-service surface: POST /scheme-changes
  // with memberId + fromSchemeId + toSchemeId in the body, GET
  // /scheme-changes/member/:memberId, and POST /scheme-changes/:id/{approve,reject,apply}.
  apiMocks.respond('POST /scheme-changes', (req: Request) => {
    const body = jsonBody(req);
    const memberId: string = body.memberId;
    const member = seed.members.find(m => m.id === memberId);
    const fromScheme = seed.schemes.find(s => s.id === body.fromSchemeId);
    const toScheme = seed.schemes.find(s => s.id === body.toSchemeId);
    let changeKind = body.changeKind as string | undefined;
    if (!changeKind && fromScheme && toScheme) {
      changeKind = fromScheme.currencyCode !== toScheme.currencyCode
        ? 'CURRENCY_CHANGE'
        : 'CROSS_GRADE';
    }
    const requested = body.effectiveDate as string;
    const today = new Date().toISOString().slice(0, 10);
    const backdated = requested < today.slice(0, 8) + '01';
    const sc: SchemeChange = {
      id: nextId('sch'),
      memberId,
      fromSchemeId: body.fromSchemeId ?? member?.schemeId ?? null,
      toSchemeId: body.toSchemeId,
      status: backdated ? 'EFFECTIVE' : 'PENDING',
      requestedDate: today,
      effectiveDate: requested,
      reason: body.reason ?? null,
      changeKind: changeKind ?? null,
      appliedAt: backdated ? new Date().toISOString() : null,
    };
    if (backdated && member) member.schemeId = sc.toSchemeId;
    seed.schemeChanges.push(sc);
    return sc;
  });
  apiMocks.respond('GET /scheme-changes/member/:memberId', (req: Request) => {
    const memberId = pathParts(req)[2];
    return seed.schemeChanges.filter(sc => sc.memberId === memberId);
  });
  apiMocks.respond('POST /scheme-changes/:changeId/approve', (req: Request) => {
    const changeId = pathParts(req)[1];
    const sc = seed.schemeChanges.find(x => x.id === changeId);
    if (sc) sc.status = 'APPROVED';
    return sc ?? { id: changeId, status: 'APPROVED' };
  });
  apiMocks.respond('POST /scheme-changes/:changeId/reject', (req: Request) => {
    const changeId = pathParts(req)[1];
    const sc = seed.schemeChanges.find(x => x.id === changeId);
    if (sc) sc.status = 'REJECTED';
    return sc ?? { id: changeId, status: 'REJECTED' };
  });
  apiMocks.respond('POST /scheme-changes/:changeId/apply', (req: Request) => {
    const changeId = pathParts(req)[1];
    const sc = seed.schemeChanges.find(x => x.id === changeId);
    if (sc) {
      sc.status = 'EFFECTIVE';
      sc.appliedAt = new Date().toISOString();
      const m = seed.members.find(x => x.id === sc.memberId);
      if (m) m.schemeId = sc.toSchemeId;
    }
    return sc ?? { id: changeId, status: 'EFFECTIVE' };
  });

  // ── Swaps ──────────────────────────────────────────────────────────
  apiMocks.respond('POST /members/:id/swaps', (req: Request) => {
    const memberId = pathParts(req)[1];
    const body = jsonBody(req);
    const sw: MemberSwap = {
      id: nextId('swp'),
      oldMemberId: memberId,
      dependantId: body.dependantId,
      newMemberId: null,
      oldDependantId: null,
      status: 'PENDING',
      requestedDate: new Date().toISOString().slice(0, 10),
      effectiveDate: body.effectiveDate,
      reason: body.reason ?? null,
    };
    seed.swaps.push(sw);
    return sw;
  });
  apiMocks.respond('GET /members/:id/swaps', (req: Request) => {
    const memberId = pathParts(req)[1];
    return seed.swaps.filter(s => s.oldMemberId === memberId);
  });
  apiMocks.respond('POST /members/:id/swaps/:swapId/approve', (req: Request) => {
    const swapId = pathParts(req)[3];
    const sw = seed.swaps.find(x => x.id === swapId);
    if (sw) {
      sw.status = 'APPLIED';
      sw.appliedAt = new Date().toISOString();
      // Simulate the promotion by swapping status labels on the pair.
      const oldMember = seed.members.find(m => m.id === sw.oldMemberId);
      const dependant = seed.dependants.find(d => d.id === sw.dependantId);
      if (oldMember && dependant) {
        sw.newMemberId = dependant.id;
        sw.oldDependantId = oldMember.id + '-as-dep';
      }
    }
    return sw ?? { id: swapId, status: 'APPLIED' };
  });
  apiMocks.respond('POST /members/:id/swaps/:swapId/reject', (req: Request) => {
    const swapId = pathParts(req)[3];
    const sw = seed.swaps.find(x => x.id === swapId);
    if (sw) sw.status = 'REJECTED';
    return sw ?? { id: swapId, status: 'REJECTED' };
  });

  // ── Contributions / charge preview / generate wizard ───────────────
  apiMocks.respond('GET /contributions/charge-preview', () =>
    seed.chargePreview ?? { subjectType: 'GROUP', subjectId: '', subjectName: '', asOf: new Date().toISOString(), periodStart: '', periodEnd: '', excludedTerminating: 0, lines: [], totals: {} });
  apiMocks.respond('POST /contributions/preview', () =>
    seed.billingPreview ?? { totalRows: 0, groupInvoicesProjected: 0, individualInvoicesProjected: 0, totalsByCurrency: {}, sample: [], cooldownActive: false, cooldownRemainingMinutes: 0, membershipModel: 'BOTH' });
  apiMocks.respond('POST /contributions/commit', () => ({
    committedRowCount: seed.billingPreview?.totalRows ?? 0,
    committedGroupInvoices: seed.billingPreview?.groupInvoicesProjected ?? 0,
    committedIndividualInvoices: seed.billingPreview?.individualInvoicesProjected ?? 0,
    totalsByCurrency: seed.billingPreview?.totalsByCurrency ?? {},
    committedAt: new Date().toISOString(),
    membershipModel: seed.billingPreview?.membershipModel ?? 'BOTH',
  }));
  apiMocks.respond('POST /contributions/billing/enqueue', (req: Request) => {
    const body = jsonBody(req);
    const configId = nextId('job');
    const runId = nextId('run');
    // Store the run as already SUCCESS so the very first poll picks up the
    // result — the wizard is designed to handle sync-dispatched jobs.
    const isCommit = body.kind === 'commit';
    const resultPayload = isCommit
      ? JSON.stringify({
          contributionsCreated: seed.billingPreview?.totalRows ?? 0,
          totalsByCurrency: Object.fromEntries(
            Object.entries(seed.billingPreview?.totalsByCurrency ?? {}).map(([k, v]) => [k, String(v)]),
          ),
          committedAt: new Date().toISOString(),
          groupInvoicesCreated: seed.billingPreview?.groupInvoicesProjected ?? 0,
          individualInvoicesCreated: seed.billingPreview?.individualInvoicesProjected ?? 0,
          membershipModel: seed.billingPreview?.membershipModel ?? 'BOTH',
        })
      : JSON.stringify({
          totalRows: seed.billingPreview?.totalRows ?? 0,
          totalsByCurrency: Object.fromEntries(
            Object.entries(seed.billingPreview?.totalsByCurrency ?? {}).map(([k, v]) => [k, String(v)]),
          ),
          sample: seed.billingPreview?.sample ?? [],
          cooldownActive: seed.billingPreview?.cooldownActive ?? false,
          cooldownRemainingMinutes: seed.billingPreview?.cooldownRemainingMinutes ?? 0,
          groupInvoicesProjected: seed.billingPreview?.groupInvoicesProjected ?? 0,
          individualInvoicesProjected: seed.billingPreview?.individualInvoicesProjected ?? 0,
          membershipModel: seed.billingPreview?.membershipModel ?? 'BOTH',
        });
    seed.jobRuns.push({
      id: runId,
      configId,
      tenantId: null,
      startedAt: new Date().toISOString(),
      endedAt: new Date().toISOString(),
      durationMs: 100,
      status: 'SUCCESS',
      triggerKind: 'manual',
      errorMessage: null,
      triggeredBy: null,
      resultPayload,
      kind: body.kind,
    });
    return { configId, runId, status: 'SUCCESS', startedAt: new Date().toISOString() };
  });
  apiMocks.respond('GET /scheduled-jobs/:configId/runs', (req: Request) => {
    const configId = pathParts(req)[1];
    return seed.jobRuns.filter(r => r.configId === configId);
  });
  apiMocks.respond('POST /contributions/billing/revoke', () => ({
    revokedRowCount: 0,
    revokedInvoiceCount: 0,
  }));

  // ── Invoices ───────────────────────────────────────────────────────
  apiMocks.respond('GET /invoices', (req: Request) => {
    const url = new URL(req.url());
    return springPage(seed.invoices, +(url.searchParams.get('page') ?? 0), +(url.searchParams.get('size') ?? 20));
  });
  apiMocks.respond('GET /invoices/:id', (req: Request) => {
    const id = pathParts(req).pop();
    return seed.invoices.find(i => i.id === id) ?? { id };
  });
  apiMocks.respond('GET /invoices/:id/statement', (req: Request) => {
    const id = pathParts(req)[1];
    const invoice = seed.invoices.find(i => i.id === id);
    if (!invoice) return { header: {}, lines: [] };
    return seed.statement ?? {
      header: {
        targetType: invoice.holderType,
        targetId: invoice.holderId,
        targetName: invoice.holderName,
        targetCode: invoice.targetCode,
        periodStart: invoice.periodStart,
        periodEnd: invoice.periodEnd,
        currencyCode: invoice.currencyCode,
      },
      lines: [
        { date: invoice.periodStart, description: 'Balance brought forward', runningBalance: 0, type: 'OPENING_BALANCE' },
        { date: invoice.periodEnd, description: 'Amount due', runningBalance: invoice.amountDue, type: 'CLOSING_BALANCE' },
      ],
    };
  });
  apiMocks.respond('GET /invoices/:id/contributions', () => []);
  apiMocks.respond('GET /invoices/group/:id', (req: Request) => {
    const groupId = pathParts(req).pop();
    return seed.invoices.filter(i => i.holderType === 'GROUP' && i.holderId === groupId);
  });

  // ── Transactions ───────────────────────────────────────────────────
  apiMocks.respond('GET /transactions', (req: Request) => {
    const url = new URL(req.url());
    return springPage(seed.transactions, +(url.searchParams.get('page') ?? 0), +(url.searchParams.get('size') ?? 20));
  });
  apiMocks.respond('POST /transactions', (req: Request) => {
    const body = jsonBody(req);
    const t: BillingTransaction = {
      id: nextId('txn'),
      amount: Number(body.amount),
      currencyCode: body.currencyCode,
      transactionTypeCode: body.transactionTypeCode,
      paymentMethodCode: body.paymentMethodCode,
      reference: body.reference,
      reason: body.reason,
      targetType: body.targetType,
      memberId: body.memberId ?? null,
      groupId: body.groupId ?? null,
      contributionId: body.contributionId ?? null,
      invoiceId: body.invoiceId ?? null,
      createdAt: new Date().toISOString(),
    };
    seed.transactions.push(t);
    return t;
  });

  // ── Statements (ledger) ────────────────────────────────────────────
  apiMocks.respond('GET /statements', () =>
    seed.statement ?? { header: {}, lines: [] });

  // ── Balances ───────────────────────────────────────────────────────
  apiMocks.respond('GET /billing/balances/bad-debts', (req: Request) => {
    const url = new URL(req.url());
    return springPage(seed.badDebts, +(url.searchParams.get('page') ?? 0), +(url.searchParams.get('size') ?? 20));
  });
  apiMocks.respond('GET /billing/balances/creditors', (req: Request) => {
    const url = new URL(req.url());
    return springPage(seed.badDebts, +(url.searchParams.get('page') ?? 0), +(url.searchParams.get('size') ?? 20));
  });
  apiMocks.respond('GET /billing/balances/members/:id', (req: Request) => {
    const memberId = pathParts(req)[2];
    return { subjectType: 'INDIVIDUAL', subjectId: memberId, currencyCode: 'USD', outstandingBalance: 0 };
  });
  apiMocks.respond('GET /billing/balances/groups/:id', (req: Request) => {
    const groupId = pathParts(req)[2];
    return { subjectType: 'GROUP', subjectId: groupId, currencyCode: 'USD', outstandingBalance: 0 };
  });

  // ── Misc catch-alls the routes may hit at nav time ─────────────────
  apiMocks.respond('GET /schemes/:id/age-groups', () => []);
  apiMocks.respond('GET /schemes/:id/benefits', () => []);
  apiMocks.respond('GET /billing/catalogue/transaction-types', () => [
    { id: 'tt-1', code: 'PAYMENT',           label: 'Payment',           sign: '+', requiresApproval: false, isActive: true },
    { id: 'tt-2', code: 'CREDIT',            label: 'Credit adjustment', sign: '+', requiresApproval: false, isActive: true },
    { id: 'tt-3', code: 'DEBIT',             label: 'Debit adjustment',  sign: '-', requiresApproval: false, isActive: true },
    { id: 'tt-4', code: 'BANK',              label: 'Bank transfer',     sign: '+', requiresApproval: false, isActive: true },
    { id: 'tt-5', code: 'FC',                label: 'FC payment',        sign: '+', requiresApproval: false, isActive: true },
    { id: 'tt-6', code: 'CTC',               label: 'CTC payment',       sign: '+', requiresApproval: false, isActive: true },
    { id: 'tt-7', code: 'DEBIT_CREDIT',      label: 'Debit/Credit note', sign: '-', requiresApproval: false, isActive: true },
  ]);
  apiMocks.respond('GET /billing/catalogue/payment-methods', () => [
    { id: 'pm-1', code: 'CASH',          label: 'Cash',          requiresReference: false, isActive: true },
    { id: 'pm-2', code: 'BANK_TRANSFER', label: 'Bank transfer', requiresReference: true,  isActive: true },
    { id: 'pm-3', code: 'ECOCASH',       label: 'EcoCash',       requiresReference: true,  isActive: true },
  ]);
  apiMocks.respond('GET /billing/catalogue/dunning', () => ({
    graceDays: 30, suspensionDays: 60, writeOffDays: 90, autoSuspend: false, autoWriteOff: false,
  }));
  apiMocks.respond('GET /billing/catalogue/cycle', () => ({
    frequency: 'MONTHLY', dayOfMonth: 1, autoGenerate: false, commitCooldownHours: 0,
  }));
  apiMocks.respond('GET /tenants/:id/member-number-config', (req: Request) => {
    const tenantId = pathParts(req)[1];
    return { tenantId, shape: 'M{seq:6}', nextSequence: 1 };
  });
}

function transitionMember(seed: BillingSeed, id: string, status: string) {
  const m = seed.members.find(x => x.id === id);
  if (m) m.status = status;
  return m ?? { id, status };
}
