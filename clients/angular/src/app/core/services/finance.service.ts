import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

export type { ReportResponse } from './report-envelope';

// ── Payment runs ────────────────────────────────────────────────────────
export type PaymentRunStatus = 'draft' | 'approved' | 'executing' | 'executed' | 'cancelled';

export interface PaymentRun {
  id: string;
  runNumber: string;
  status: PaymentRunStatus;
  /** V072 — every run is homogeneous over payeeType (either PROVIDER
   *  or MEMBER); trigger on payment_run_items enforces the invariant. */
  payeeType?: PayeeType;
  totalAmount: string;
  currencyCode: string;
  paymentCount: number;
  description?: string;
  executedAt?: string;
  executedBy?: string;
  /** V067 — snapshot of the unpaid balance rolled in from prior runs at
   *  generation time. Zero when nothing was rolled forward (also the
   *  default until the item-population flow lands). */
  carriedInAmount?: string;
  /** V067 — snapshot of the unpaid balance remaining when this run was
   *  executed. Rolls into the next run's carriedInAmount. */
  carriedOutAmount?: string;
  /** V067 — moment the last item in the run transitioned to paid.
   *  Null until every item in the run is settled. */
  settlementDate?: string;
  /** V075 — id of the tenant bank account this run debits. */
  sourceBankAccountId?: string;
  /** V075 — friendly label of the source bank account. Populated by the
   *  paginated list query; may be undefined on single-row loads. */
  sourceBankAccountLabel?: string;
  createdAt: string;
  updatedAt?: string;
  createdBy?: string;
}

export interface CreatePaymentRunPayload {
  currencyCode: string;
  description?: string;
  /** PROVIDER | MEMBER — omit to accept the server default (PROVIDER). */
  payeeType?: PayeeType;
  /** V075 — the tenant bank account this run debits. Required; server
   *  rejects if its currency does not match currencyCode. */
  sourceBankAccountId: string;
}

export type PayeeType = 'PROVIDER' | 'MEMBER';

export interface PaymentRunItem {
  id: string;
  paymentRunId: string;
  paymentId?: string;
  providerId?: string;
  memberId?: string;
  payeeType: PayeeType;
  amount: string;
  currencyCode: string;
  status: 'pending' | 'scheduled' | 'paid' | 'withheld' | 'skipped';
  createdAt: string;
}

// ── Payments ────────────────────────────────────────────────────────────
export type PaymentStatus = 'pending' | 'approved' | 'paid' | 'cancelled' | 'failed';

export interface Payment {
  id: string;
  paymentNumber: string;
  providerId?: string;
  memberId?: string;
  payeeType: PayeeType;
  amount: string;
  currencyCode: string;
  paymentType?: string;
  status: PaymentStatus;
  paymentMethod?: string;
  reference?: string;
  paidAt?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface CreatePaymentPayload {
  providerId: string;
  amount: string;
  currencyCode: string;
  paymentType?: string;
  paymentMethod?: string;
  reference?: string;
}

// ── Provider balance (single-record shape; the paginated row shape lives
//     under CreditorRow now that the finance-side Creditors listing unifies
//     providers + members). ────────────────────────────────────────────────
export interface ProviderBalance {
  id: string;
  providerId: string;
  totalClaimed: string;
  totalApproved: string;
  totalPaid: string;
  outstandingBalance: string;
  currencyCode: string;
  lastUpdatedAt?: string;
  createdAt: string;
}

/**
 * Member-side counterpart to {@link ProviderBalance}. A member can carry
 * a distinct balance per currency, so the detail endpoint returns a Flux —
 * consumers hold an array and render one row per currency in the summary
 * card.
 */
export interface MemberBalance {
  id: string;
  memberId: string;
  memberName?: string;
  memberCode?: string;
  memberEmail?: string;
  totalClaimed: string;
  totalApproved: string;
  totalPaid: string;
  outstandingBalance: string;
  currencyCode: string;
  lastUpdatedAt?: string;
  createdAt?: string;
}

// ── Note (V074: unified debit / credit / memo notes) ────────────────────
export type NoteDirection = 'DEBIT' | 'CREDIT';
export type NoteType =
  | 'TAX_WITHHELD' | 'WRITE_OFF' | 'GOODWILL' | 'ENDORSEMENT_PREMIUM'
  | 'PREMIUM_REFUND' | 'PROVIDER_OVERPAYMENT_RECOVERY' | 'MEMO';
export type NoteStatus = 'pending' | 'approved' | 'applied' | 'reversed';
export type NoteVariant = 'ORIGINAL' | 'REVERSAL';

export interface Note {
  id: string;
  noteNumber: string;
  providerId?: string;
  memberId?: string;
  direction: NoteDirection;
  noteType: NoteType;
  type: NoteVariant;
  reversesNoteId?: string;
  amount: string;
  currencyCode: string;
  reason?: string;
  status: NoteStatus;
  approvedBy?: string;
  approvedAt?: string;
  postedAt?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface CreateNotePayload {
  providerId?: string;
  memberId?: string;
  direction: NoteDirection;
  noteType: NoteType;
  amount: string;
  currencyCode: string;
  reason?: string;
}

/**
 * Row shape returned by GET /notes/page. Member + provider display
 * fields pre-joined server-side so tables render inline without a
 * second lookup.
 */
export interface NoteRow {
  id: string;
  noteNumber: string;
  providerId?: string;
  providerName?: string;
  memberId?: string;
  memberName?: string;
  memberNumber?: string;
  direction: NoteDirection;
  noteType: NoteType;
  type: NoteVariant;
  reversesNoteId?: string;
  amount: string;
  currencyCode: string;
  reason?: string;
  status: NoteStatus;
  approvedBy?: string;
  approvedAt?: string;
  postedAt?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface NotePageParams {
  status?: NoteStatus | '';
  direction?: NoteDirection | '';
  noteType?: NoteType | '';
  providerId?: string;
  memberId?: string;
  currencyCode?: string;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

// ── Bank reconciliation ─────────────────────────────────────────────────
export type ReconciliationStatus = 'unmatched' | 'matched' | 'investigating' | 'resolved';

export interface BankReconciliation {
  id: string;
  referenceNumber: string;
  statementAmount: string;
  systemAmount: string;
  difference: string;
  currencyCode: string;
  status: ReconciliationStatus;
  notes?: string;
  statementDate: string;
  reconciledAt?: string;
  reconciledBy?: string;
  createdAt: string;
}

export interface CreateReconciliationPayload {
  referenceNumber: string;
  statementAmount: string;
  currencyCode: string;
  statementDate: string;
  notes?: string;
}

// ── Tenant bank accounts ────────────────────────────────────────────────
export interface TenantBankAccount {
  id: string;
  bankName: string;
  accountNumber: string;
  branchCode?: string;
  swiftCode?: string;
  accountName: string;
  currencyCode: string;
  label: string;
  notes?: string;
  nominated: boolean;
  active: boolean;
  createdAt: string;
  updatedAt?: string;
}

export interface UpsertTenantBankAccountPayload {
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
}

// ── CTC payments ────────────────────────────────────────────────────────
/**
 * V069 lifecycle status. `draft` awaits operator commit; `committed` has
 * posted a `member_payable_applications` row and a `CTC_OFFSET` transaction;
 * `reversed` means the original was superseded by a compensating REVERSAL
 * row (the reversal itself is stored as a fresh row with `type='REVERSAL'`
 * and `status='committed'`).
 */
export type CtcPaymentStatus = 'draft' | 'committed' | 'reversed';
export type CtcPaymentType = 'CTC' | 'REVERSAL';

export interface CtcPayment {
  id: string;
  type?: CtcPaymentType;
  status?: CtcPaymentStatus;
  groupId?: string;
  memberId?: string;
  memberPayableId?: string;
  reversesCtcId?: string;
  amount: string;
  currencyCode: string;
  contributionId?: string;
  committed: boolean;
  createdAt: string;
  createdBy?: string;
  committedAt?: string;
  committedBy?: string;
}

/**
 * Row shape returned by GET /ctc-payments/page. Member and group
 * display names are pre-joined server-side so the operational CTC list
 * renders the beneficiary chip inline without a lookup. `type`, `status`,
 * and `memberPayableId` (V069) drive the per-row action gates on the
 * finance-side list.
 */
export interface CtcPaymentRow {
  id: string;
  groupId?: string;
  groupName?: string;
  memberId?: string;
  memberName?: string;
  memberNumber?: string;
  amount: string;
  currencyCode: string;
  contributionId?: string;
  committed: boolean;
  createdAt: string;
  createdBy?: string;
  type?: CtcPaymentType;
  status?: CtcPaymentStatus;
  memberPayableId?: string;
}

/**
 * Outstanding "approved claim amount owed to member" ledger row —
 * returned by GET /api/v1/member-payables/member/{memberId}. Feeds the
 * two-step selector on the CTC form so an operator picks a specific
 * payable to offset rather than typing a raw UUID.
 */
export interface MemberPayable {
  id: string;
  memberId: string;
  claimId: string;
  claimNumber?: string;
  amount: string;
  currencyCode: string;
  status: 'open' | 'applied' | 'reversed';
  recordedAt: string;
  recordedBy?: string;
}

export interface MemberPayableOutstanding {
  currencyCode: string;
  outstanding: string;
}

export interface ReverseCtcPaymentPayload {
  reason: string;
}

export interface CtcPaymentPageParams {
  committed?: boolean;
  currencyCode?: string;
  memberId?: string;
  q?: string;
  /**
   * Filter to auto-drafted rows (createdBy IS NULL) when true, or to
   * operator-created rows when false. Omit for both. Feeds the "recent
   * auto-drafts" panel on /tenant/claims/ctc/auto.
   */
  systemDrafted?: boolean;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

/**
 * Envelope shape mirrored by every server-side paginated endpoint
 * across the platform (claims-service, contributions-service,
 * finance-service, user-service). Kept local so finance.service.ts
 * doesn't reach into balance.service.ts for a shared symbol.
 */
export interface FinancePageResponse<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

// ── Payment paginated row ───────────────────────────────────────────────
export interface PaymentRow {
  id: string;
  paymentNumber: string;
  providerId?: string;
  providerName?: string;
  memberId?: string;
  memberName?: string;
  payeeType: PayeeType;
  payeeName?: string;
  amount: string;
  currencyCode: string;
  paymentType: string;
  status: PaymentStatus;
  paymentMethod?: string;
  reference?: string;
  paidAt?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface PaymentPageParams {
  status?: PaymentStatus | '';
  paymentType?: string;
  providerId?: string;
  currencyCode?: string;
  /** V067 — scope the list to a single payment run via the payment_run_items join. */
  paymentRunId?: string;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

// ── Advance-payment paginated row ───────────────────────────────────────
export interface AdvancePaymentRow {
  id: string;
  paymentId?: string;
  providerId?: string;
  providerName?: string;
  memberId?: string;
  memberName?: string;
  memberNumber?: string;
  amount: string;
  currencyCode: string;
  paymentMethod?: string;
  reference?: string;
  comment?: string;
  recordedAt?: string;
  // Row-level status + type surface for actions gating; the paged endpoint
  // may not yet project these — treat as optional and default in the UI.
  type?: 'ADVANCE' | 'REVERSAL';
  status?: 'pending' | 'approved' | 'applied' | 'reversed';
}

export interface AdvancePaymentPageParams {
  providerId?: string;
  memberId?: string;
  currencyCode?: string;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

// ── Payment-run paginated row (shares the Angular PaymentRun type) ─────
export interface PaymentRunPageParams {
  status?: string;
  currencyCode?: string;
  /** PROVIDER | MEMBER — omit to show both. */
  payeeType?: PayeeType | '';
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

// ── Unified creditor row (providers + members) — feeds the finance-side
//     /tenant/finance/creditors page. Both halves render the same four
//     money columns; subjectType disambiguates the row and drives the
//     detail navigation. ────────────────────────────────────────────────────
export type CreditorSubjectType = 'PROVIDER' | 'MEMBER';

export interface CreditorRow {
  subjectType: CreditorSubjectType;
  subjectId: string;
  subjectCode?: string;
  subjectName?: string;
  subjectEmail?: string;
  currencyCode: string;
  totalClaimed: string;
  totalApproved: string;
  totalPaid: string;
  outstandingBalance: string;
  lastActivityAt?: string;
}

export interface CreditorPageParams {
  /** PROVIDER | MEMBER | BOTH — omit for BOTH. */
  subjectType?: CreditorSubjectType | 'BOTH';
  currencyCode?: string;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

// ── Bank reconciliation paginated params (uses BankReconciliation directly) ─
export interface BankReconciliationPageParams {
  status?: string;
  currencyCode?: string;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

export interface CreateCtcPaymentPayload {
  /** Member-only for MVP — group-only CTC is out of scope. */
  memberId: string;
  /**
   * Required — the source member_payable row this CTC offsets. Server
   * 422s if omitted; the two-step form on `/finance/payments/ctc/add`
   * enforces the selection.
   */
  memberPayableId: string;
  amount: string;
  currencyCode: string;
  contributionId?: string;
  groupId?: string;
}

// ── Advance payments ────────────────────────────────────────────────────
export type AdvancePaymentType = 'ADVANCE' | 'REVERSAL';
export type AdvancePaymentStatus = 'pending' | 'approved' | 'applied' | 'reversed';

export interface AdvancePayment {
  id: string;
  paymentId?: string;
  providerId?: string;
  memberId?: string;
  amount: string;
  currencyCode: string;
  paymentMethod?: string;
  reference?: string;
  comment?: string;
  recordedAt: string;
  recordedBy?: string;
  type?: AdvancePaymentType;
  status?: AdvancePaymentStatus;
  approvedBy?: string;
  approvedAt?: string;
  reversesAdvanceId?: string;
}

export interface AdvancePaymentApplication {
  id: string;
  advancePaymentId: string;
  paymentId?: string;
  paymentRunId?: string;
  paymentRunItemId?: string;
  amountApplied: string;
  currencyCode: string;
  appliedAt: string;
  appliedBy?: string;
}

export interface CreateAdvancePaymentPayload {
  providerId?: string;
  memberId?: string;
  amount: string;
  currencyCode: string;
  paymentMethod?: string;
  reference?: string;
  comment?: string;
}

export interface ReverseAdvancePaymentPayload {
  reason: string;
}

// ── Persisted advice records ────────────────────────────────────────────
export interface PaymentAdviceRecord {
  id: string;
  adviceNumber?: string;
  paymentRunId?: string;
  payeeType: PayeeType;
  providerId?: string;
  memberId?: string;
  currencyCode: string;
  totalAmount: string;
  claimCount: number;
  documentUrl?: string;
  excelUrl?: string;
  status: 'generated' | 'sent' | 'failed';
  issuedAt: string;
  periodStartAt?: string;
  periodEndAt?: string;
  carriedInAmount?: string;
  claimsPaidAmount?: string;
  ctcAppliedAmount?: string;
  advanceAppliedAmount?: string;
  taxWithheldAmount?: string;
  shortfallAmount?: string;
  netDueAmount?: string;
  createdAt: string;
}

// ── Paginated advice row ────────────────────────────────────────────────
/** Row shape returned by GET /payment-advices/page — extends the
 *  record with joined runNumber + payeeName so the list can render
 *  friendly labels without a follow-up round-trip. */
export interface PaymentAdviceRow {
  id: string;
  adviceNumber?: string;
  paymentRunId?: string;
  runNumber?: string;
  payeeType: PayeeType;
  providerId?: string;
  memberId?: string;
  payeeName?: string;
  currencyCode: string;
  totalAmount: string;
  claimCount: number;
  status: 'generated' | 'sent' | 'failed';
  issuedAt: string;
  periodStartAt?: string;
  periodEndAt?: string;
  carriedInAmount?: string;
  claimsPaidAmount?: string;
  ctcAppliedAmount?: string;
  advanceAppliedAmount?: string;
  taxWithheldAmount?: string;
  shortfallAmount?: string;
  netDueAmount?: string;
  createdAt: string;
}

export interface PaymentAdvicePageParams {
  paymentRunId?: string;
  providerId?: string;
  memberId?: string;
  payeeType?: PayeeType | '';
  status?: 'generated' | 'sent' | 'failed' | '';
  currencyCode?: string;
  /** ISO-8601 datetime, inclusive lower bound. */
  periodStart?: string;
  /** ISO-8601 datetime, inclusive upper bound. */
  periodEnd?: string;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

// ── Payment advice ledger ───────────────────────────────────────────────
export type PaymentAdviceLineType =
  | 'CARRY_FORWARD' | 'CLAIM_PAID' | 'NOTE_DEBIT' | 'CTC_APPLIED'
  | 'ADVANCE_APPLIED' | 'TAX_WITHHELD' | 'SHORTFALL' | 'NOTE_CREDIT';

export interface PaymentAdviceLine {
  lineType: PaymentAdviceLineType;
  referenceType?: string;
  referenceId?: string;
  description?: string;
  debitAmount: string;
  creditAmount: string;
  currencyCode: string;
  postedAt: string;
  sequence: number;
  /**
   * When set, this line is a late-arriving note whose {@code postedAt}
   * fell inside a prior run's window but wasn't stamped onto that run's
   * advice. Points at that prior run — the UI renders a "back-period
   * from run …" badge to make the timing legible.
   */
  backPeriodRunId?: string;
}

export interface PaymentAdvice {
  adviceNumber: string;
  paymentRunId: string;
  runNumber?: string;
  payeeType: PayeeType;
  providerId?: string;
  providerName?: string;
  memberId?: string;
  memberName?: string;
  currencyCode: string;
  periodStartAt?: string;
  periodEndAt?: string;
  generatedAt: string;
  carriedInAmount: string;
  claimsPaidAmount: string;
  ctcAppliedAmount: string;
  advanceAppliedAmount: string;
  taxWithheldAmount: string;
  shortfallAmount: string;
  netDueAmount: string;
  lines: PaymentAdviceLine[];
}

@Injectable({ providedIn: 'root' })
export class FinanceService {
  constructor(private api: ApiService) {}

  // ── Payment advice ──
  /**
   * Server-side paginated payment-advices list. Feeds /tenant/finance/advice.
   * Payee name + run number are pre-joined server-side.
   */
  listAdvicesPaged(
    opts: PaymentAdvicePageParams & { reportingCurrency?: string },
  ): Observable<ReportResponse<FinancePageResponse<PaymentAdviceRow>>> {
    const params: Record<string, string> = {};
    if (opts.paymentRunId)   params['paymentRunId']  = opts.paymentRunId;
    if (opts.providerId)     params['providerId']    = opts.providerId;
    if (opts.memberId)       params['memberId']      = opts.memberId;
    if (opts.payeeType)      params['payeeType']     = opts.payeeType;
    if (opts.status)         params['status']        = opts.status;
    if (opts.currencyCode)   params['currencyCode']  = opts.currencyCode;
    if (opts.periodStart)    params['periodStart']   = opts.periodStart;
    if (opts.periodEnd)      params['periodEnd']     = opts.periodEnd;
    if (opts.q)              params['q']             = opts.q;
    if (opts.sortKey)        params['sortKey']       = opts.sortKey;
    if (opts.sortDirection)  params['sortDirection'] = opts.sortDirection;
    if (opts.page !== undefined) params['page']      = String(opts.page);
    if (opts.size !== undefined) params['size']      = String(opts.size);
    if (opts.reportingCurrency) params['reportingCurrency'] = opts.reportingCurrency;
    return this.api.get<ReportResponse<FinancePageResponse<PaymentAdviceRow>>>(
      '/payment-advices/page', params,
    );
  }
  listAdvicesForRun(paymentRunId: string): Observable<PaymentAdviceRecord[]> {
    return this.api.get<PaymentAdviceRecord[]>(`/payment-runs/${paymentRunId}/advices`);
  }
  getAdvice(id: string): Observable<PaymentAdvice> {
    return this.api.get<PaymentAdvice>(`/payment-advices/${id}`);
  }
  regenerateAdvicesForRun(paymentRunId: string): Observable<PaymentAdvice[]> {
    return this.api.post<PaymentAdvice[]>(`/payment-runs/${paymentRunId}/advices/regenerate`, {});
  }

  // ── Payment runs ──
  listRuns(): Observable<PaymentRun[]> { return this.api.get<PaymentRun[]>('/payment-runs'); }
  /** Server-side paginated payment-runs list. */
  listRunsPaged(opts: PaymentRunPageParams): Observable<FinancePageResponse<PaymentRun>> {
    const params: Record<string, string> = {};
    if (opts.status)         params['status']         = opts.status;
    if (opts.currencyCode)   params['currencyCode']   = opts.currencyCode;
    if (opts.payeeType)      params['payeeType']      = opts.payeeType;
    if (opts.q)              params['q']              = opts.q;
    if (opts.sortKey)        params['sortKey']        = opts.sortKey;
    if (opts.sortDirection)  params['sortDirection']  = opts.sortDirection;
    if (opts.page !== undefined) params['page']       = String(opts.page);
    if (opts.size !== undefined) params['size']       = String(opts.size);
    return this.api.get<FinancePageResponse<PaymentRun>>('/payment-runs/page', params);
  }
  getRun(id: string): Observable<PaymentRun> { return this.api.get<PaymentRun>(`/payment-runs/${id}`); }
  getRunItems(id: string): Observable<PaymentRunItem[]> { return this.api.get<PaymentRunItem[]>(`/payment-runs/${id}/items`); }
  createRun(body: CreatePaymentRunPayload): Observable<PaymentRun> { return this.api.post<PaymentRun>('/payment-runs', body); }
  executeRun(id: string): Observable<PaymentRun> { return this.api.post<PaymentRun>(`/payment-runs/${id}/execute`, {}); }
  approveRun(id: string): Observable<PaymentRun> { return this.api.post<PaymentRun>(`/payment-runs/${id}/approve`, {}); }
  cancelRun(id: string): Observable<PaymentRun> { return this.api.post<PaymentRun>(`/payment-runs/${id}/cancel`, {}); }
  /** Phase 7: multi-sheet XLSX workbook for a payment run (D7-1..D7-3, D7-6). */
  exportPaymentRunWorkbook(id: string): Observable<Blob> {
    return this.api.getBlob(`/payment-runs/${id}/export/excel`);
  }

  // ── Payments ──
  listPayments(): Observable<Payment[]> { return this.api.get<Payment[]>('/payments'); }
  getPayment(id: string): Observable<Payment> { return this.api.get<Payment>(`/payments/${id}`); }
  getPaymentsByProvider(providerId: string): Observable<Payment[]> { return this.api.get<Payment[]>(`/payments/provider/${providerId}`); }
  getPaymentsByMember(memberId: string): Observable<Payment[]> { return this.api.get<Payment[]>(`/payments/member/${memberId}`); }
  getPaymentsByStatus(status: PaymentStatus): Observable<Payment[]> { return this.api.get<Payment[]>(`/payments/status/${status}`); }
  /**
   * Server-side paginated payments list. Feeds /tenant/finance/payments.
   * Provider name pre-joined.
   */
  listPaymentsPaged(opts: PaymentPageParams): Observable<FinancePageResponse<PaymentRow>> {
    const params: Record<string, string> = {};
    if (opts.status)         params['status']         = opts.status;
    if (opts.paymentType)    params['paymentType']    = opts.paymentType;
    if (opts.providerId)     params['providerId']     = opts.providerId;
    if (opts.currencyCode)   params['currencyCode']   = opts.currencyCode;
    if (opts.paymentRunId)   params['paymentRunId']   = opts.paymentRunId;
    if (opts.q)              params['q']              = opts.q;
    if (opts.sortKey)        params['sortKey']        = opts.sortKey;
    if (opts.sortDirection)  params['sortDirection']  = opts.sortDirection;
    if (opts.page !== undefined) params['page']       = String(opts.page);
    if (opts.size !== undefined) params['size']       = String(opts.size);
    return this.api.get<FinancePageResponse<PaymentRow>>('/payments/page', params);
  }
  createPayment(body: CreatePaymentPayload): Observable<Payment> { return this.api.post<Payment>('/payments', body); }
  payPayment(id: string): Observable<Payment> { return this.api.post<Payment>(`/payments/${id}/pay`, {}); }
  cancelPayment(id: string): Observable<Payment> { return this.api.post<Payment>(`/payments/${id}/cancel`, {}); }
  /**
   * Revoke (hard-delete) a pending payment from its parent run. Permitted
   * only while payment.status='pending' AND the parent PaymentRun is in
   * draft or approved status; the payee retains their outstanding balance
   * and will be picked up by the next run generation.
   */
  revokePayment(id: string): Observable<void> { return this.api.delete<void>(`/payments/${id}`); }

  // ── Creditors (unified: providers + members) ──
  /**
   * Server-side paginated unified creditors list. Feeds
   * /tenant/finance/creditors. subjectType=PROVIDER|MEMBER|BOTH.
   */
  listCreditorsPaged(
    opts: CreditorPageParams & { reportingCurrency?: string },
  ): Observable<ReportResponse<FinancePageResponse<CreditorRow>>> {
    const params: Record<string, string> = {};
    if (opts.subjectType)      params['subjectType']       = opts.subjectType;
    if (opts.currencyCode)     params['currencyCode']      = opts.currencyCode;
    if (opts.q)                params['q']                 = opts.q;
    if (opts.sortKey)          params['sortKey']           = opts.sortKey;
    if (opts.sortDirection)    params['sortDirection']     = opts.sortDirection;
    if (opts.page !== undefined)   params['page']          = String(opts.page);
    if (opts.size !== undefined)   params['size']          = String(opts.size);
    if (opts.reportingCurrency)    params['reportingCurrency'] = opts.reportingCurrency;
    return this.api.get<ReportResponse<FinancePageResponse<CreditorRow>>>(
      '/creditors/page', params,
    );
  }
  /** Provider creditor detail — mirrors the pre-Phase-4 provider balance shape. */
  getCreditorProviderDetail(providerId: string): Observable<ProviderBalance> {
    return this.api.get<ProviderBalance>(`/creditors/provider/${providerId}`);
  }
  /** Member creditor detail — one row per currency the member carries a balance in. */
  getCreditorMemberDetail(memberId: string): Observable<MemberBalance[]> {
    return this.api.get<MemberBalance[]>(`/creditors/member/${memberId}`);
  }
  /** Excel export of the unified creditors list (same filter shape as listCreditorsPaged). */
  exportCreditorsExcel(opts: { subjectType?: CreditorSubjectType | 'BOTH'; currencyCode?: string; q?: string }): Observable<Blob> {
    const params: Record<string, string> = {};
    if (opts.subjectType)  params['subjectType']  = opts.subjectType;
    if (opts.currencyCode) params['currencyCode'] = opts.currencyCode;
    if (opts.q)            params['q']            = opts.q;
    return this.api.getBlob('/creditors/export/excel', params);
  }

  // ── Notes (V074: unified debit / credit / memo — replaced Adjustment) ──
  getNotesByProvider(providerId: string): Observable<Note[]> { return this.api.get<Note[]>(`/notes/provider/${providerId}`); }
  getNotesByMember(memberId: string): Observable<Note[]> { return this.api.get<Note[]>(`/notes/member/${memberId}`); }
  getNotesByStatus(status: NoteStatus): Observable<Note[]> { return this.api.get<Note[]>(`/notes/status/${status}`); }
  /**
   * Server-side paginated notes list. Feeds /tenant/finance/debit-notes
   * (direction=DEBIT), /credit-notes (direction=CREDIT), /notes
   * (combined), and /reports/withheld-tax (which pins
   * noteType='TAX_WITHHELD'). Rows carry pre-joined member + provider
   * names.
   */
  listNotesPaged(
    opts: NotePageParams & { reportingCurrency?: string },
  ): Observable<ReportResponse<FinancePageResponse<NoteRow>>> {
    const params: Record<string, string> = {};
    if (opts.status)          params['status']         = opts.status;
    if (opts.direction)       params['direction']      = opts.direction;
    if (opts.noteType)        params['noteType']       = opts.noteType;
    if (opts.providerId)      params['providerId']     = opts.providerId;
    if (opts.memberId)        params['memberId']       = opts.memberId;
    if (opts.currencyCode)    params['currencyCode']   = opts.currencyCode;
    if (opts.q)               params['q']              = opts.q;
    if (opts.sortKey)         params['sortKey']        = opts.sortKey;
    if (opts.sortDirection)   params['sortDirection']  = opts.sortDirection;
    if (opts.page !== undefined) params['page']        = String(opts.page);
    if (opts.size !== undefined) params['size']        = String(opts.size);
    if (opts.reportingCurrency) params['reportingCurrency'] = opts.reportingCurrency;
    return this.api.get<ReportResponse<FinancePageResponse<NoteRow>>>('/notes/page', params);
  }
  getNote(id: string): Observable<Note> { return this.api.get<Note>(`/notes/${id}`); }
  createNote(body: CreateNotePayload): Observable<Note> { return this.api.post<Note>('/notes', body); }
  approveNote(id: string): Observable<Note> { return this.api.post<Note>(`/notes/${id}/approve`, {}); }
  applyNote(id: string): Observable<Note> { return this.api.post<Note>(`/notes/${id}/apply`, {}); }
  /**
   * Applied notes go through reverse — inserts a compensating REVERSAL
   * row of opposite direction and flips the original to
   * status='reversed'. Pending / approved notes go through
   * {@link deleteNote} instead (they never hit the ledger).
   */
  reverseNote(id: string, reason?: string): Observable<Note> {
    return this.api.post<Note>(`/notes/${id}/reverse`, reason ? { reason } : {});
  }
  deleteNote(id: string): Observable<void> { return this.api.delete<void>(`/notes/${id}`); }

  // ── Tenant bank accounts ──
  listTenantBankAccounts(): Observable<TenantBankAccount[]> { return this.api.get<TenantBankAccount[]>('/tenant-bank-accounts'); }
  getTenantBankAccount(id: string): Observable<TenantBankAccount> { return this.api.get<TenantBankAccount>(`/tenant-bank-accounts/${id}`); }
  createTenantBankAccount(body: UpsertTenantBankAccountPayload): Observable<TenantBankAccount> { return this.api.post<TenantBankAccount>('/tenant-bank-accounts', body); }
  updateTenantBankAccount(id: string, body: UpsertTenantBankAccountPayload): Observable<TenantBankAccount> { return this.api.put<TenantBankAccount>(`/tenant-bank-accounts/${id}`, body); }
  deleteTenantBankAccount(id: string): Observable<void> { return this.api.delete<void>(`/tenant-bank-accounts/${id}`); }

  // ── CTC payments ──
  listCtcPayments(committed?: boolean): Observable<CtcPayment[]> {
    return this.api.get<CtcPayment[]>('/ctc-payments', committed === undefined ? {} : { committed: String(committed) });
  }
  /**
   * Server-side paginated CTC-payments list. Feeds
   * /tenant/claims/ctc/{pending,committed}. Rows carry pre-joined
   * member + group display fields.
   */
  listCtcPaymentsPaged(opts: CtcPaymentPageParams): Observable<FinancePageResponse<CtcPaymentRow>> {
    const params: Record<string, string> = {};
    if (opts.committed !== undefined) params['committed']     = String(opts.committed);
    if (opts.currencyCode)            params['currencyCode']  = opts.currencyCode;
    if (opts.memberId)                params['memberId']      = opts.memberId;
    if (opts.q)                       params['q']             = opts.q;
    if (opts.systemDrafted !== undefined) params['systemDrafted'] = String(opts.systemDrafted);
    if (opts.sortKey)                 params['sortKey']       = opts.sortKey;
    if (opts.sortDirection)           params['sortDirection'] = opts.sortDirection;
    if (opts.page !== undefined)      params['page']          = String(opts.page);
    if (opts.size !== undefined)      params['size']          = String(opts.size);
    return this.api.get<FinancePageResponse<CtcPaymentRow>>('/ctc-payments/page', params);
  }
  getCtcPayment(id: string): Observable<CtcPayment> { return this.api.get<CtcPayment>(`/ctc-payments/${id}`); }
  createCtcPayment(body: CreateCtcPaymentPayload): Observable<CtcPayment> { return this.api.post<CtcPayment>('/ctc-payments', body); }
  commitCtcPayment(id: string): Observable<CtcPayment> { return this.api.post<CtcPayment>(`/ctc-payments/${id}/commit`, {}); }
  reverseCtcPayment(id: string, body: ReverseCtcPaymentPayload): Observable<CtcPayment> {
    return this.api.post<CtcPayment>(`/ctc-payments/${id}/reverse`, body);
  }

  // ── Member payables (feeds the CTC form's payable selector) ──
  listOpenPayablesForMember(memberId: string): Observable<MemberPayable[]> {
    return this.api.get<MemberPayable[]>(`/member-payables/member/${memberId}`);
  }
  getMemberPayableBalance(memberId: string): Observable<MemberPayableOutstanding[]> {
    return this.api.get<MemberPayableOutstanding[]>(`/member-payables/member/${memberId}/balance`);
  }

  // ── Advance payments ──
  listAdvancePayments(): Observable<AdvancePayment[]> { return this.api.get<AdvancePayment[]>('/advance-payments'); }
  /** Server-side paginated advance-payments list — provider + member names joined. */
  listAdvancePaymentsPaged(opts: AdvancePaymentPageParams): Observable<FinancePageResponse<AdvancePaymentRow>> {
    const params: Record<string, string> = {};
    if (opts.providerId)    params['providerId']    = opts.providerId;
    if (opts.memberId)      params['memberId']      = opts.memberId;
    if (opts.currencyCode)  params['currencyCode']  = opts.currencyCode;
    if (opts.q)             params['q']             = opts.q;
    if (opts.sortKey)       params['sortKey']       = opts.sortKey;
    if (opts.sortDirection) params['sortDirection'] = opts.sortDirection;
    if (opts.page !== undefined) params['page']     = String(opts.page);
    if (opts.size !== undefined) params['size']     = String(opts.size);
    return this.api.get<FinancePageResponse<AdvancePaymentRow>>('/advance-payments/page', params);
  }
  getAdvancePayment(id: string): Observable<AdvancePayment> { return this.api.get<AdvancePayment>(`/advance-payments/${id}`); }
  createAdvancePayment(body: CreateAdvancePaymentPayload): Observable<AdvancePayment> { return this.api.post<AdvancePayment>('/advance-payments', body); }
  approveAdvancePayment(id: string): Observable<AdvancePayment> { return this.api.post<AdvancePayment>(`/advance-payments/${id}/approve`, {}); }
  reverseAdvancePayment(id: string, body: ReverseAdvancePaymentPayload): Observable<AdvancePayment> {
    return this.api.post<AdvancePayment>(`/advance-payments/${id}/reverse`, body);
  }
  listAdvancePaymentApplications(advanceId: string): Observable<AdvancePaymentApplication[]> {
    return this.api.get<AdvancePaymentApplication[]>(`/advance-payments/${advanceId}/applications`);
  }

  // ── Reconciliations ──
  listReconciliations(): Observable<BankReconciliation[]> { return this.api.get<BankReconciliation[]>('/reconciliations'); }
  /** Server-side paginated reconciliations list. */
  listReconciliationsPaged(opts: BankReconciliationPageParams): Observable<FinancePageResponse<BankReconciliation>> {
    const params: Record<string, string> = {};
    if (opts.status)         params['status']         = opts.status;
    if (opts.currencyCode)   params['currencyCode']   = opts.currencyCode;
    if (opts.q)              params['q']              = opts.q;
    if (opts.sortKey)        params['sortKey']        = opts.sortKey;
    if (opts.sortDirection)  params['sortDirection']  = opts.sortDirection;
    if (opts.page !== undefined) params['page']       = String(opts.page);
    if (opts.size !== undefined) params['size']       = String(opts.size);
    return this.api.get<FinancePageResponse<BankReconciliation>>('/reconciliations/page', params);
  }
  getReconciliationsByStatus(status: ReconciliationStatus): Observable<BankReconciliation[]> { return this.api.get<BankReconciliation[]>(`/reconciliations/status/${status}`); }
  createReconciliation(body: CreateReconciliationPayload): Observable<BankReconciliation> { return this.api.post<BankReconciliation>('/reconciliations', body); }
  matchReconciliation(id: string): Observable<BankReconciliation> { return this.api.post<BankReconciliation>(`/reconciliations/${id}/match`, {}); }
  investigateReconciliation(id: string): Observable<BankReconciliation> { return this.api.post<BankReconciliation>(`/reconciliations/${id}/investigate`, {}); }
  resolveReconciliation(id: string): Observable<BankReconciliation> { return this.api.post<BankReconciliation>(`/reconciliations/${id}/resolve`, {}); }

  // ── Billing reports (Phase 2) ──────────────────────────────────────────
  /** Per-scheme billing aggregate for a window. Rows stay native currency. */
  getSchemeBillingReport(opts: BillingReportParams): Observable<ReportResponse<SchemeBillingSummaryRow[]>> {
    return this.api.get<ReportResponse<SchemeBillingSummaryRow[]>>('/reports/billing/schemes', billingParams(opts));
  }
  getSchemeBillingDetail(schemeId: string, opts: BillingReportParams): Observable<ReportResponse<SchemeBillingDetailResponse>> {
    return this.api.get<ReportResponse<SchemeBillingDetailResponse>>(`/reports/billing/schemes/${schemeId}`, billingParams(opts));
  }
  exportSchemeBillingExcel(opts: BillingReportParams): Observable<Blob> {
    return this.api.getBlob('/reports/billing/schemes/export/excel', billingParams(opts));
  }
  /** Per-group billing aggregate — only rows with a group_id + committed. */
  getGroupBillingReport(opts: BillingReportParams): Observable<ReportResponse<GroupBillingSummaryRow[]>> {
    return this.api.get<ReportResponse<GroupBillingSummaryRow[]>>('/reports/billing/groups', billingParams(opts));
  }
  getGroupBillingDetail(groupId: string, opts: BillingReportParams): Observable<ReportResponse<GroupBillingDetailResponse>> {
    return this.api.get<ReportResponse<GroupBillingDetailResponse>>(`/reports/billing/groups/${groupId}`, billingParams(opts));
  }
  exportGroupBillingExcel(opts: BillingReportParams): Observable<Blob> {
    return this.api.getBlob('/reports/billing/groups/export/excel', billingParams(opts));
  }

  // ── Billing per-member (Phase 3 §8 owed-back to Phase 2) ───────────────
  /** Per-member billing aggregate — paginated + searchable. */
  getMemberBillingReport(opts: MemberBillingReportParams): Observable<ReportResponse<FinancePageResponse<MemberBillingSummaryRow>>> {
    return this.api.get<ReportResponse<FinancePageResponse<MemberBillingSummaryRow>>>(
      '/reports/billing/members', memberReportParams(opts));
  }
  getMemberBillingDetail(memberId: string, opts: BillingReportParams): Observable<ReportResponse<MemberBillingDetailResponse>> {
    return this.api.get<ReportResponse<MemberBillingDetailResponse>>(
      `/reports/billing/members/${memberId}`, billingParams(opts));
  }
  exportMemberBillingExcel(opts: MemberBillingReportParams): Observable<Blob> {
    return this.api.getBlob('/reports/billing/members/export/excel', memberReportParams(opts));
  }

  // ── Receipts reports (Phase 3) ─────────────────────────────────────────
  getSchemeReceiptsReport(opts: BillingReportParams): Observable<ReportResponse<ReceiptsSummaryRow[]>> {
    return this.api.get<ReportResponse<ReceiptsSummaryRow[]>>('/reports/receipts/schemes', billingParams(opts));
  }
  exportSchemeReceiptsExcel(opts: BillingReportParams): Observable<Blob> {
    return this.api.getBlob('/reports/receipts/schemes/export/excel', billingParams(opts));
  }
  getGroupReceiptsReport(opts: BillingReportParams): Observable<ReportResponse<ReceiptsSummaryRow[]>> {
    return this.api.get<ReportResponse<ReceiptsSummaryRow[]>>('/reports/receipts/groups', billingParams(opts));
  }
  exportGroupReceiptsExcel(opts: BillingReportParams): Observable<Blob> {
    return this.api.getBlob('/reports/receipts/groups/export/excel', billingParams(opts));
  }
  getMemberReceiptsReport(opts: MemberBillingReportParams): Observable<ReportResponse<FinancePageResponse<ReceiptsSummaryRow>>> {
    return this.api.get<ReportResponse<FinancePageResponse<ReceiptsSummaryRow>>>(
      '/reports/receipts/members', memberReportParams(opts));
  }
  exportMemberReceiptsExcel(opts: MemberBillingReportParams): Observable<Blob> {
    return this.api.getBlob('/reports/receipts/members/export/excel', memberReportParams(opts));
  }

  /**
   * Receipts drill-down for a single scheme / group / member. Pass
   * {@code unallocated=true} on the scheme dimension to fetch the
   * synthetic "Unallocated group payments" bucket.
   */
  getReceiptsDetail(dimension: 'scheme' | 'group' | 'member', id: string,
                    opts: ReceiptsDetailParams): Observable<ReportResponse<ReceiptsDetailResponse>> {
    return this.api.get<ReportResponse<ReceiptsDetailResponse>>(
      `/reports/receipts/${dimension}s/${id}`, receiptsDetailParams(opts));
  }
  exportReceiptsDetailExcel(dimension: 'scheme' | 'group' | 'member', id: string,
                            opts: ReceiptsDetailParams): Observable<Blob> {
    return this.api.getBlob(`/reports/receipts/${dimension}s/${id}/export/excel`, receiptsDetailParams(opts));
  }

  // ── Cash-flow forecast (Phase 8) ─────────────────────────────────────────
  getCashFlowForecast(asOf: string, rollingWeeks: number, reportingCurrency?: string):
      Observable<ReportResponse<CashFlowForecastResponse>> {
    const params: Record<string, string> = { asOf, rollingWeeks: String(rollingWeeks) };
    if (reportingCurrency) params['reportingCurrency'] = reportingCurrency;
    return this.api.get<ReportResponse<CashFlowForecastResponse>>('/reports/cash-flow-forecast', params);
  }
  exportCashFlowForecastExcel(asOf: string, rollingWeeks: number): Observable<Blob> {
    return this.api.getBlob('/reports/cash-flow-forecast/export/excel', { asOf, rollingWeeks: String(rollingWeeks) });
  }

  // ── Collection-rate trend (Phase 8) ──────────────────────────────────────
  getCollectionRateTrend(opts: BillingReportParams): Observable<ReportResponse<CollectionRateTrendResponse>> {
    return this.api.get<ReportResponse<CollectionRateTrendResponse>>('/reports/collection-rate-trend', billingParams(opts));
  }
  exportCollectionRateTrendExcel(opts: BillingReportParams): Observable<Blob> {
    return this.api.getBlob('/reports/collection-rate-trend/export/excel', billingParams(opts));
  }

  // ── Collection rate (Phase 3) ──────────────────────────────────────────
  getCollectionRate(opts: BillingReportParams): Observable<ReportResponse<CollectionRateReportResponse>> {
    return this.api.get<ReportResponse<CollectionRateReportResponse>>('/reports/collection-rate', billingParams(opts));
  }
  exportCollectionRateExcel(opts: BillingReportParams): Observable<Blob> {
    return this.api.getBlob('/reports/collection-rate/export/excel', billingParams(opts));
  }

  // ── Cross-service reports (Phase 5) ─────────────────────────────────────
  getLossRatio(opts: BillingReportParams): Observable<ReportResponse<LossRatioReportResponse>> {
    return this.api.get<ReportResponse<LossRatioReportResponse>>('/reports/billing-vs-claims', billingParams(opts));
  }
  exportLossRatioExcel(opts: BillingReportParams): Observable<Blob> {
    return this.api.getBlob('/reports/billing-vs-claims/export/excel', billingParams(opts));
  }
  getMemberPayments(opts: BillingReportParams): Observable<ReportResponse<MemberPaymentsReportResponse>> {
    return this.api.get<ReportResponse<MemberPaymentsReportResponse>>('/reports/member-payments', billingParams(opts));
  }
  exportMemberPaymentsExcel(opts: BillingReportParams): Observable<Blob> {
    return this.api.getBlob('/reports/member-payments/export/excel', billingParams(opts));
  }

  // ── Balance history (Phase 6) ─────────────────────────────────────────────
  getProviderBalanceHistory(providerId: string, opts: BalanceHistoryParams = {}): Observable<ReportResponse<BalanceHistoryResponse>> {
    return this.api.get<ReportResponse<BalanceHistoryResponse>>(
      `/reports/balance-history/provider/${providerId}`, balanceHistoryParams(opts));
  }
  exportProviderBalanceHistoryExcel(providerId: string, opts: BalanceHistoryParams = {}): Observable<Blob> {
    return this.api.getBlob(`/reports/balance-history/provider/${providerId}/export/excel`, balanceHistoryParams(opts));
  }
  getMemberBalanceHistory(memberId: string, opts: BalanceHistoryParams = {}): Observable<ReportResponse<BalanceHistoryResponse>> {
    return this.api.get<ReportResponse<BalanceHistoryResponse>>(
      `/reports/balance-history/member/${memberId}`, balanceHistoryParams(opts));
  }
  exportMemberBalanceHistoryExcel(memberId: string, opts: BalanceHistoryParams = {}): Observable<Blob> {
    return this.api.getBlob(`/reports/balance-history/member/${memberId}/export/excel`, balanceHistoryParams(opts));
  }
}

// ── Billing report types (Phase 2) ─────────────────────────────────────────

export interface BillingReportParams {
  periodStart: string;                 // ISO date, inclusive
  periodEnd: string;                   // ISO date, inclusive
  reportingCurrency?: string;          // ISO-4217 override; blank = tenant default
}

function billingParams(opts: BillingReportParams): Record<string, string> {
  const params: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.reportingCurrency) params['reportingCurrency'] = opts.reportingCurrency;
  return params;
}

export interface SchemeBillingSummaryRow {
  schemeId: string;
  schemeName: string;
  insuranceLine: string;
  currencyCode: string;
  principalCount: number;
  dependantCount: number;
  livesCovered: number;
  ageBand0_18: string;
  ageBand19_35: string;
  ageBand36_55: string;
  ageBand56Plus: string;
  totalBilled: string;
  totalPaid: string;
}

export interface GroupBillingSummaryRow {
  groupId: string;
  groupName: string;
  currencyCode: string;
  principalCount: number;
  dependantCount: number;
  livesCovered: number;
  totalBilled: string;
  totalPaid: string;
}

export interface BillingMonthlyBucket {
  periodStart: string;                 // ISO date — first day of the month
  currencyCode: string;
  principalCount: number;
  dependantCount: number;
  totalBilled: string;
  totalPaid: string;
}

export interface SchemeBillingDetailResponse {
  schemeId: string;
  schemeName: string;
  insuranceLine: string;
  perCurrencySummary: SchemeBillingSummaryRow[];
  monthlyBreakdown: BillingMonthlyBucket[];
}

export interface GroupBillingDetailResponse {
  groupId: string;
  groupName: string;
  perCurrencySummary: GroupBillingSummaryRow[];
  monthlyBreakdown: BillingMonthlyBucket[];
}

// ── Billing per-member + receipts + collection-rate (Phase 3) ──────────────

export interface MemberBillingReportParams {
  periodStart: string;
  periodEnd: string;
  reportingCurrency?: string;
  search?: string;
  insuranceLine?: string;
  schemeId?: string;
  page?: number;
  size?: number;
}

function memberReportParams(opts: MemberBillingReportParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  if (opts.search)            p['search']            = opts.search;
  if (opts.insuranceLine)     p['insuranceLine']     = opts.insuranceLine;
  if (opts.schemeId)          p['schemeId']          = opts.schemeId;
  if (opts.page  !== undefined) p['page'] = String(opts.page);
  if (opts.size  !== undefined) p['size'] = String(opts.size);
  return p;
}

export interface MemberBillingSummaryRow {
  memberId: string;
  memberNumber: string;
  memberName: string;
  insuranceLine: string;
  schemeName: string;
  currencyCode: string;
  contributionCount: number;
  totalBilled: string;
  totalPaid: string;
}

export interface MemberBillingDetailResponse {
  memberId: string;
  memberNumber: string;
  memberName: string;
  insuranceLine: string;
  summary: MemberBillingSummaryRow[];
  monthly: BillingMonthlyBucket[];
}

/**
 * Receipts summary row — same shape for scheme / group / member. The
 * {@code dimensionName} carries whatever a treasurer scans by:
 * "Gold Plan" for schemes, "Acme Corp" for groups,
 * "M-001 — Alice Zulu" for members. {@code dimensionId} is
 * {@code null} for the synthetic "Unallocated group payments" bucket.
 */
export interface ReceiptsSummaryRow {
  dimensionId: string | null;
  dimensionName: string;
  insuranceLine: string | null;
  currencyCode: string;
  totalReceived: string;
  transactionCount: number;
}

export interface ReceiptsDetailParams {
  periodStart: string;
  periodEnd: string;
  transactionType?: string;
  currency?: string;
  reportingCurrency?: string;
  unallocated?: boolean;
  page?: number;
  size?: number;
}

function receiptsDetailParams(opts: ReceiptsDetailParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.transactionType)   p['transactionType']  = opts.transactionType;
  if (opts.currency)          p['currency']         = opts.currency;
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  if (opts.unallocated)       p['unallocated']      = 'true';
  if (opts.page !== undefined) p['page'] = String(opts.page);
  if (opts.size !== undefined) p['size'] = String(opts.size);
  return p;
}

export interface ReceiptsDetailResponse {
  dimensionId: string | null;
  dimensionName: string;
  monthlyBuckets: ReceiptsMonthlyBucket[];
  transactions: FinancePageResponse<TransactionLedgerRow>;
}

export interface ReceiptsMonthlyBucket {
  month: string;
  currencyCode: string;
  totalReceived: string;
  transactionCount: number;
}

export interface TransactionLedgerRow {
  id: string;
  transactionNumber: string;
  transactionDate: string;
  transactionType: string;
  paymentMethod: string;
  reference: string;
  amount: string;
  currencyCode: string;
}

export interface CollectionRateReportResponse {
  periodStart: string;
  periodEnd: string;
  byScheme: CollectionRateDimensionRow[];
  byGroup: CollectionRateDimensionRow[];
  byMember: CollectionRateDimensionRow[];
}

export interface CollectionRateDimensionRow {
  dimensionId: string;
  dimensionName: string;
  currencyCode: string;
  monthlyBuckets: CollectionRateMonthlyBucket[];
  totalBilled: string;
  totalReceived: string;
  totalRatePct: string | null;
}

export interface CollectionRateMonthlyBucket {
  month: string;
  billed: string;
  received: string;
  ratePct: string | null;
}

// ── Cross-service reports (Phase 5) ────────────────────────────────────────

/**
 * Loss ratio — billing vs claims at scheme level, per currency. Mirrors the
 * Java record `LossRatioReportResponse`. {@code paidRatioPct} is null when
 * nothing was billed for the window (server renders it as a dash).
 */
export interface LossRatioReportResponse {
  periodStart: string;
  periodEnd: string;
  rows: LossRatioRow[];
}

export interface LossRatioRow {
  schemeId: string;
  schemeName: string;
  currencyCode: string;
  totalBilled: string;
  totalClaimed: string;
  totalApproved: string;
  totalPaid: string;
  paidRatioPct: string | null;
  billedMinusPaid: string;
}

/**
 * Member payments — unified billing / receipts / claims-paid per member, per
 * currency. Mirrors the Java record `MemberPaymentsReportResponse`.
 */
export interface MemberPaymentsReportResponse {
  periodStart: string;
  periodEnd: string;
  rows: MemberPaymentRow[];
}

export interface MemberPaymentRow {
  memberId: string;
  memberName: string;
  currencyCode: string;
  totalBilled: string;
  totalReceived: string;
  totalClaimsPaid: string;
  netPosition: string;
}

// ── Balance history (Phase 6) ───────────────────────────────────────────────

/**
 * Filters for the per-payee balance-history report. {@code asAtRun} is an
 * exact payment-run id — omitted → full history, newest first (D6-4).
 * {@code currency} narrows to one native currency.
 */
export interface BalanceHistoryParams {
  asAtRun?: string;
  currency?: string;
}

function balanceHistoryParams(opts: BalanceHistoryParams): Record<string, string> {
  const params: Record<string, string> = {};
  if (opts.asAtRun) params['asAtRun'] = opts.asAtRun;
  if (opts.currency) params['currency'] = opts.currency;
  return params;
}

/**
 * Balance history — freeze-frame of a payee's balance at each executed
 * payment run. Mirrors the Java record `BalanceHistoryResponse`.
 * {@code payeeName} is the joined display name ("" when the payee no
 * longer exists). Amounts are native per-currency strings (G34).
 */
export interface BalanceHistoryResponse {
  payeeId: string;
  payeeName: string;
  rows: BalanceHistoryRow[];
}

export interface BalanceHistoryRow {
  runId: string;
  runNumber: string;
  executedAt: string;
  currencyCode: string;
  openingBalance: string;
  closingBalance: string;
  totalClaimed: string;
  totalApproved: string;
  totalPaid: string;
  netDue: string;
}

// ── Cash-flow forecast + collection-rate trend (Phase 8) ───────────────────

/**
 * 13-week rolling cash-flow forecast. Mirrors the Java record
 * `CashFlowForecastResponse`. Window is [asOf, asOf + rollingWeeks*7);
 * series are per native currency with a full weekly strip each (zero
 * weeks included). Never cross-currency — the envelope's fxRates is
 * deliberately empty (D8-7).
 */
export interface CashFlowForecastResponse {
  asOf: string;
  rollingWeeks: number;
  windowStart: string;
  windowEnd: string;
  series: CashFlowCurrencySeries[];
}

export interface CashFlowCurrencySeries {
  currencyCode: string;
  totalInflow: string;
  totalOutflow: string;
  totalNet: string;
  buckets: CashFlowWeekBucket[];
}

export interface CashFlowWeekBucket {
  weekStart: string;
  inflow: string;
  outflow: string;
  net: string;
}

/**
 * Portfolio-level collection-rate trend. Mirrors the Java record
 * `CollectionRateTrendResponse`. One (month, currency) row — the
 * per-dimension stacks summed across all dimensions (D8-3).
 */
export interface CollectionRateTrendResponse {
  periodStart: string;
  periodEnd: string;
  months: CollectionRateTrendMonth[];
}

export interface CollectionRateTrendMonth {
  month: string;
  currencyCode: string;
  billed: string;
  received: string;
  ratePct: string | null;
}

