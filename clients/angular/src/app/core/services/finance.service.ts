import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

// ── Payment runs ────────────────────────────────────────────────────────
export type PaymentRunStatus = 'draft' | 'approved' | 'executing' | 'executed' | 'cancelled';

export interface PaymentRun {
  id: string;
  runNumber: string;
  status: PaymentRunStatus;
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
  createdAt: string;
  updatedAt?: string;
  createdBy?: string;
}

export interface CreatePaymentRunPayload {
  currencyCode: string;
  description?: string;
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

// ── Provider balance ────────────────────────────────────────────────────
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

// ── Adjustment ──────────────────────────────────────────────────────────
export type AdjustmentType =
  | 'IN_PAYMENT' | 'PAYOUT' | 'NON_CASH_IN' | 'NON_CASH_OUT' | 'TAX_WITHHELD';
export type AdjustmentStatus = 'pending' | 'approved' | 'applied' | 'cancelled';

export interface Adjustment {
  id: string;
  adjustmentNumber: string;
  providerId?: string;
  memberId?: string;
  adjustmentType: AdjustmentType;
  amount: string;
  currencyCode: string;
  reason?: string;
  status: AdjustmentStatus;
  approvedBy?: string;
  approvedAt?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface CreateAdjustmentPayload {
  providerId?: string;
  memberId?: string;
  adjustmentType: AdjustmentType;
  amount: string;
  currencyCode: string;
  reason?: string;
}

/**
 * Row shape returned by GET /adjustments/page. Member + provider display
 * fields pre-joined server-side so tables render inline without a
 * second lookup.
 */
export interface AdjustmentRow {
  id: string;
  adjustmentNumber: string;
  providerId?: string;
  providerName?: string;
  memberId?: string;
  memberName?: string;
  memberNumber?: string;
  adjustmentType: AdjustmentType;
  amount: string;
  currencyCode: string;
  reason?: string;
  status: AdjustmentStatus;
  approvedBy?: string;
  approvedAt?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface AdjustmentPageParams {
  status?: AdjustmentStatus | '';
  adjustmentType?: AdjustmentType | '';
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

// ── MASCA bank accounts ─────────────────────────────────────────────────
export interface MascaBankAccount {
  id: string;
  bankName: string;
  accountNumber: string;
  branchCode?: string;
  swiftCode?: string;
  accountName: string;
  currencyCode: string;
  nominated: boolean;
  active: boolean;
  createdAt: string;
  updatedAt?: string;
}

export interface UpsertMascaBankAccountPayload {
  bankName: string;
  accountNumber: string;
  branchCode?: string;
  swiftCode?: string;
  accountName: string;
  currencyCode: string;
  nominated?: boolean;
  active?: boolean;
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
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

// ── Provider balance (creditor) paginated row ──────────────────────────
export interface ProviderBalanceRow {
  id: string;
  providerId: string;
  providerName?: string;
  totalClaimed: string;
  totalApproved: string;
  totalPaid: string;
  outstandingBalance: string;
  currencyCode: string;
  lastUpdatedAt?: string;
}

export interface ProviderBalancePageParams {
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

// ── Finance-note paginated params ──────────────────────────────────────
export interface FinanceNotePageParams {
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

// ── Debit / credit notes ────────────────────────────────────────────────
export interface FinanceNote {
  id: string;
  amount: string;
  currencyCode: string;
  reference?: string;
  taskId?: string;
  notes?: string;
  createdAt: string;
  createdBy?: string;
}

export interface CreateNotePayload {
  amount: string;
  currencyCode: string;
  reference?: string;
  taskId?: string;
  notes?: string;
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
  | 'CARRY_FORWARD' | 'CLAIM_PAID' | 'CTC_APPLIED'
  | 'ADVANCE_APPLIED' | 'TAX_WITHHELD' | 'SHORTFALL';

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
  listAdvicesPaged(opts: PaymentAdvicePageParams): Observable<FinancePageResponse<PaymentAdviceRow>> {
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
    return this.api.get<FinancePageResponse<PaymentAdviceRow>>('/payment-advices/page', params);
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

  // ── Payments ──
  listPayments(): Observable<Payment[]> { return this.api.get<Payment[]>('/payments'); }
  getPayment(id: string): Observable<Payment> { return this.api.get<Payment>(`/payments/${id}`); }
  getPaymentsByProvider(providerId: string): Observable<Payment[]> { return this.api.get<Payment[]>(`/payments/provider/${providerId}`); }
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

  // ── Provider balances ──
  listProviderBalances(): Observable<ProviderBalance[]> { return this.api.get<ProviderBalance[]>('/provider-balances'); }
  /** Server-side paginated provider-balances list — provider name joined. */
  listProviderBalancesPaged(opts: ProviderBalancePageParams): Observable<FinancePageResponse<ProviderBalanceRow>> {
    const params: Record<string, string> = {};
    if (opts.currencyCode)   params['currencyCode']  = opts.currencyCode;
    if (opts.q)              params['q']             = opts.q;
    if (opts.sortKey)        params['sortKey']       = opts.sortKey;
    if (opts.sortDirection)  params['sortDirection'] = opts.sortDirection;
    if (opts.page !== undefined) params['page']      = String(opts.page);
    if (opts.size !== undefined) params['size']      = String(opts.size);
    return this.api.get<FinancePageResponse<ProviderBalanceRow>>('/provider-balances/page', params);
  }
  getProviderBalance(providerId: string): Observable<ProviderBalance> { return this.api.get<ProviderBalance>(`/provider-balances/provider/${providerId}`); }

  // ── Adjustments ──
  getAdjustmentsByProvider(providerId: string): Observable<Adjustment[]> { return this.api.get<Adjustment[]>(`/adjustments/provider/${providerId}`); }
  getAdjustmentsByStatus(status: AdjustmentStatus): Observable<Adjustment[]> { return this.api.get<Adjustment[]>(`/adjustments/status/${status}`); }
  /**
   * Server-side paginated adjustments list. Feeds both the general
   * adjustments page and the tax-withheld page (which pins
   * adjustmentType='TAX_WITHHELD'). Rows carry pre-joined member +
   * provider names.
   */
  listAdjustmentsPaged(opts: AdjustmentPageParams): Observable<FinancePageResponse<AdjustmentRow>> {
    const params: Record<string, string> = {};
    if (opts.status)          params['status']         = opts.status;
    if (opts.adjustmentType)  params['adjustmentType'] = opts.adjustmentType;
    if (opts.providerId)      params['providerId']     = opts.providerId;
    if (opts.memberId)        params['memberId']       = opts.memberId;
    if (opts.currencyCode)    params['currencyCode']   = opts.currencyCode;
    if (opts.q)               params['q']              = opts.q;
    if (opts.sortKey)         params['sortKey']        = opts.sortKey;
    if (opts.sortDirection)   params['sortDirection']  = opts.sortDirection;
    if (opts.page !== undefined) params['page']        = String(opts.page);
    if (opts.size !== undefined) params['size']        = String(opts.size);
    return this.api.get<FinancePageResponse<AdjustmentRow>>('/adjustments/page', params);
  }
  getAdjustment(id: string): Observable<Adjustment> { return this.api.get<Adjustment>(`/adjustments/${id}`); }
  createAdjustment(body: CreateAdjustmentPayload): Observable<Adjustment> { return this.api.post<Adjustment>('/adjustments', body); }
  approveAdjustment(id: string): Observable<Adjustment> { return this.api.post<Adjustment>(`/adjustments/${id}/approve`, {}); }
  applyAdjustment(id: string): Observable<Adjustment> { return this.api.post<Adjustment>(`/adjustments/${id}/apply`, {}); }
  cancelAdjustment(id: string): Observable<Adjustment> { return this.api.post<Adjustment>(`/adjustments/${id}/cancel`, {}); }

  // ── MASCA bank accounts ──
  listMascaBankAccounts(): Observable<MascaBankAccount[]> { return this.api.get<MascaBankAccount[]>('/masca-bank-accounts'); }
  getMascaBankAccount(id: string): Observable<MascaBankAccount> { return this.api.get<MascaBankAccount>(`/masca-bank-accounts/${id}`); }
  createMascaBankAccount(body: UpsertMascaBankAccountPayload): Observable<MascaBankAccount> { return this.api.post<MascaBankAccount>('/masca-bank-accounts', body); }
  updateMascaBankAccount(id: string, body: UpsertMascaBankAccountPayload): Observable<MascaBankAccount> { return this.api.put<MascaBankAccount>(`/masca-bank-accounts/${id}`, body); }
  deleteMascaBankAccount(id: string): Observable<void> { return this.api.delete<void>(`/masca-bank-accounts/${id}`); }

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

  // ── Debit / credit notes ──
  listDebitNotes(): Observable<FinanceNote[]> { return this.api.get<FinanceNote[]>('/debit-notes'); }
  listDebitNotesPaged(opts: FinanceNotePageParams): Observable<FinancePageResponse<FinanceNote>> {
    return this.notePage('/debit-notes/page', opts);
  }
  createDebitNote(body: CreateNotePayload): Observable<FinanceNote> { return this.api.post<FinanceNote>('/debit-notes', body); }
  listCreditNotes(): Observable<FinanceNote[]> { return this.api.get<FinanceNote[]>('/credit-notes'); }
  listCreditNotesPaged(opts: FinanceNotePageParams): Observable<FinancePageResponse<FinanceNote>> {
    return this.notePage('/credit-notes/page', opts);
  }
  createCreditNote(body: CreateNotePayload): Observable<FinanceNote> { return this.api.post<FinanceNote>('/credit-notes', body); }

  private notePage(path: string, opts: FinanceNotePageParams): Observable<FinancePageResponse<FinanceNote>> {
    const params: Record<string, string> = {};
    if (opts.currencyCode)   params['currencyCode']  = opts.currencyCode;
    if (opts.q)              params['q']             = opts.q;
    if (opts.sortKey)        params['sortKey']       = opts.sortKey;
    if (opts.sortDirection)  params['sortDirection'] = opts.sortDirection;
    if (opts.page !== undefined) params['page']      = String(opts.page);
    if (opts.size !== undefined) params['size']      = String(opts.size);
    return this.api.get<FinancePageResponse<FinanceNote>>(path, params);
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
}
