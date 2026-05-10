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

@Injectable({ providedIn: 'root' })
export class FinanceService {
  constructor(private api: ApiService) {}

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

  // ── Reconciliations ──
  listReconciliations(): Observable<BankReconciliation[]> { return this.api.get<BankReconciliation[]>('/reconciliations'); }
  getReconciliationsByStatus(status: ReconciliationStatus): Observable<BankReconciliation[]> { return this.api.get<BankReconciliation[]>(`/reconciliations/status/${status}`); }
  createReconciliation(body: CreateReconciliationPayload): Observable<BankReconciliation> { return this.api.post<BankReconciliation>('/reconciliations', body); }
  matchReconciliation(id: string): Observable<BankReconciliation> { return this.api.post<BankReconciliation>(`/reconciliations/${id}/match`, {}); }
}
