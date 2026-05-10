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
  createdAt: string;
  updatedAt?: string;
  createdBy?: string;
}

export interface CreatePaymentRunPayload {
  currencyCode: string;
  description?: string;
}

export interface PaymentRunItem {
  id: string;
  paymentRunId: string;
  paymentId?: string;
  providerId: string;
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
  providerId: string;
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
export interface CtcPayment {
  id: string;
  groupId?: string;
  memberId?: string;
  amount: string;
  currencyCode: string;
  contributionId?: string;
  committed: boolean;
  createdAt: string;
  createdBy?: string;
}

export interface CreateCtcPaymentPayload {
  groupId?: string;
  memberId?: string;
  amount: string;
  currencyCode: string;
  contributionId?: string;
}

// ── Advance payments ────────────────────────────────────────────────────
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
  paymentRunId?: string;
  providerId?: string;
  currencyCode: string;
  totalAmount: string;
  claimCount: number;
  documentUrl?: string;
  excelUrl?: string;
  status: 'generated' | 'sent' | 'failed';
  issuedAt: string;
  createdAt: string;
}

// ── Payment advice ──────────────────────────────────────────────────────
export interface PaymentAdviceLine {
  claimNumber: string;
  memberName: string;
  claimedAmount: string;
  approvedAmount: string;
  paidAmount: string;
  serviceDate: string;
}

export interface PaymentAdvice {
  adviceNumber: string;
  providerId?: string;
  providerName?: string;
  totalAmount: string;
  currencyCode: string;
  generatedAt: string;
  lines: PaymentAdviceLine[];
}

@Injectable({ providedIn: 'root' })
export class FinanceService {
  constructor(private api: ApiService) {}

  // ── Payment advice ──
  generateAdvice(paymentRunId: string): Observable<PaymentAdvice> {
    return this.api.get<PaymentAdvice>(`/payment-advices/run/${paymentRunId}`);
  }
  listAdviceRecords(): Observable<PaymentAdviceRecord[]> {
    return this.api.get<PaymentAdviceRecord[]>('/payment-advices');
  }
  listAdviceRecordsForRun(paymentRunId: string): Observable<PaymentAdviceRecord[]> {
    return this.api.get<PaymentAdviceRecord[]>('/payment-advices', { paymentRunId });
  }

  // ── Payment runs ──
  listRuns(): Observable<PaymentRun[]> { return this.api.get<PaymentRun[]>('/payment-runs'); }
  getRun(id: string): Observable<PaymentRun> { return this.api.get<PaymentRun>(`/payment-runs/${id}`); }
  getRunItems(id: string): Observable<PaymentRunItem[]> { return this.api.get<PaymentRunItem[]>(`/payment-runs/${id}/items`); }
  createRun(body: CreatePaymentRunPayload): Observable<PaymentRun> { return this.api.post<PaymentRun>('/payment-runs', body); }
  executeRun(id: string): Observable<PaymentRun> { return this.api.post<PaymentRun>(`/payment-runs/${id}/execute`, {}); }

  // ── Payments ──
  listPayments(): Observable<Payment[]> { return this.api.get<Payment[]>('/payments'); }
  getPayment(id: string): Observable<Payment> { return this.api.get<Payment>(`/payments/${id}`); }
  getPaymentsByProvider(providerId: string): Observable<Payment[]> { return this.api.get<Payment[]>(`/payments/provider/${providerId}`); }
  getPaymentsByStatus(status: PaymentStatus): Observable<Payment[]> { return this.api.get<Payment[]>(`/payments/status/${status}`); }
  createPayment(body: CreatePaymentPayload): Observable<Payment> { return this.api.post<Payment>('/payments', body); }
  payPayment(id: string): Observable<Payment> { return this.api.post<Payment>(`/payments/${id}/pay`, {}); }

  // ── Provider balances ──
  listProviderBalances(): Observable<ProviderBalance[]> { return this.api.get<ProviderBalance[]>('/provider-balances'); }
  getProviderBalance(providerId: string): Observable<ProviderBalance> { return this.api.get<ProviderBalance>(`/provider-balances/provider/${providerId}`); }

  // ── Adjustments ──
  getAdjustmentsByProvider(providerId: string): Observable<Adjustment[]> { return this.api.get<Adjustment[]>(`/adjustments/provider/${providerId}`); }
  getAdjustmentsByStatus(status: AdjustmentStatus): Observable<Adjustment[]> { return this.api.get<Adjustment[]>(`/adjustments/status/${status}`); }
  getAdjustment(id: string): Observable<Adjustment> { return this.api.get<Adjustment>(`/adjustments/${id}`); }
  createAdjustment(body: CreateAdjustmentPayload): Observable<Adjustment> { return this.api.post<Adjustment>('/adjustments', body); }
  approveAdjustment(id: string): Observable<Adjustment> { return this.api.post<Adjustment>(`/adjustments/${id}/approve`, {}); }
  applyAdjustment(id: string): Observable<Adjustment> { return this.api.post<Adjustment>(`/adjustments/${id}/apply`, {}); }

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
  createCtcPayment(body: CreateCtcPaymentPayload): Observable<CtcPayment> { return this.api.post<CtcPayment>('/ctc-payments', body); }
  commitCtcPayment(id: string): Observable<CtcPayment> { return this.api.post<CtcPayment>(`/ctc-payments/${id}/commit`, {}); }

  // ── Advance payments ──
  listAdvancePayments(): Observable<AdvancePayment[]> { return this.api.get<AdvancePayment[]>('/advance-payments'); }
  getAdvancePayment(id: string): Observable<AdvancePayment> { return this.api.get<AdvancePayment>(`/advance-payments/${id}`); }
  createAdvancePayment(body: CreateAdvancePaymentPayload): Observable<AdvancePayment> { return this.api.post<AdvancePayment>('/advance-payments', body); }

  // ── Debit / credit notes ──
  listDebitNotes(): Observable<FinanceNote[]> { return this.api.get<FinanceNote[]>('/debit-notes'); }
  createDebitNote(body: CreateNotePayload): Observable<FinanceNote> { return this.api.post<FinanceNote>('/debit-notes', body); }
  listCreditNotes(): Observable<FinanceNote[]> { return this.api.get<FinanceNote[]>('/credit-notes'); }
  createCreditNote(body: CreateNotePayload): Observable<FinanceNote> { return this.api.post<FinanceNote>('/credit-notes', body); }

  // ── Reconciliations ──
  listReconciliations(): Observable<BankReconciliation[]> { return this.api.get<BankReconciliation[]>('/reconciliations'); }
  getReconciliationsByStatus(status: ReconciliationStatus): Observable<BankReconciliation[]> { return this.api.get<BankReconciliation[]>(`/reconciliations/status/${status}`); }
  createReconciliation(body: CreateReconciliationPayload): Observable<BankReconciliation> { return this.api.post<BankReconciliation>('/reconciliations', body); }
  matchReconciliation(id: string): Observable<BankReconciliation> { return this.api.post<BankReconciliation>(`/reconciliations/${id}/match`, {}); }
}
