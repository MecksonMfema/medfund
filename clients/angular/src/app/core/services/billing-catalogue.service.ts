import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface BenefitType {
  id: string;
  code: string;
  label: string;
  description?: string;
  sortOrder: number;
  isActive: boolean;
  updatedAt?: string;
}

export interface PaymentMethod {
  id: string;
  code: string;
  label: string;
  description?: string;
  requiresReference: boolean;
  isActive: boolean;
  updatedAt?: string;
}

export interface TransactionType {
  id: string;
  code: string;
  label: string;
  description?: string;
  sign: '+' | '-';
  requiresApproval: boolean;
  isActive: boolean;
  updatedAt?: string;
}

export interface DunningConfig {
  graceDays: number;
  suspensionDays: number;
  writeOffDays: number;
  autoSuspend: boolean;
  autoWriteOff: boolean;
  updatedAt?: string;
}

export interface BillingCycleConfig {
  frequency: 'MONTHLY' | 'QUARTERLY' | 'ANNUAL' | 'CUSTOM';
  dayOfMonth: number;
  autoGenerate: boolean;
  commitCooldownHours: number;
  lastCommittedAt?: string;
  updatedAt?: string;
}

export interface UpsertBenefitTypePayload {
  code: string;
  label: string;
  description?: string;
  sortOrder?: number;
  isActive?: boolean;
}

export interface UpsertPaymentMethodPayload {
  code: string;
  label: string;
  description?: string;
  requiresReference?: boolean;
  isActive?: boolean;
}

export interface UpsertTransactionTypePayload {
  code: string;
  label: string;
  description?: string;
  sign: '+' | '-';
  requiresApproval?: boolean;
  isActive?: boolean;
}

@Injectable({ providedIn: 'root' })
export class BillingCatalogueService {
  private base = '/billing/catalogue';

  constructor(private api: ApiService) {}

  // ── Benefit types ──
  listBenefitTypes(activeOnly = false): Observable<BenefitType[]> {
    return this.api.get<BenefitType[]>(`${this.base}/benefit-types`, { activeOnly: String(activeOnly) });
  }
  createBenefitType(body: UpsertBenefitTypePayload): Observable<BenefitType> {
    return this.api.post<BenefitType>(`${this.base}/benefit-types`, body);
  }
  updateBenefitType(id: string, body: UpsertBenefitTypePayload): Observable<BenefitType> {
    return this.api.put<BenefitType>(`${this.base}/benefit-types/${id}`, body);
  }
  deleteBenefitType(id: string): Observable<void> {
    return this.api.delete<void>(`${this.base}/benefit-types/${id}`);
  }

  // ── Payment methods ──
  listPaymentMethods(activeOnly = false): Observable<PaymentMethod[]> {
    return this.api.get<PaymentMethod[]>(`${this.base}/payment-methods`, { activeOnly: String(activeOnly) });
  }
  createPaymentMethod(body: UpsertPaymentMethodPayload): Observable<PaymentMethod> {
    return this.api.post<PaymentMethod>(`${this.base}/payment-methods`, body);
  }
  updatePaymentMethod(id: string, body: UpsertPaymentMethodPayload): Observable<PaymentMethod> {
    return this.api.put<PaymentMethod>(`${this.base}/payment-methods/${id}`, body);
  }
  deletePaymentMethod(id: string): Observable<void> {
    return this.api.delete<void>(`${this.base}/payment-methods/${id}`);
  }

  // ── Transaction types ──
  listTransactionTypes(activeOnly = false): Observable<TransactionType[]> {
    return this.api.get<TransactionType[]>(`${this.base}/transaction-types`, { activeOnly: String(activeOnly) });
  }
  createTransactionType(body: UpsertTransactionTypePayload): Observable<TransactionType> {
    return this.api.post<TransactionType>(`${this.base}/transaction-types`, body);
  }
  updateTransactionType(id: string, body: UpsertTransactionTypePayload): Observable<TransactionType> {
    return this.api.put<TransactionType>(`${this.base}/transaction-types/${id}`, body);
  }
  deleteTransactionType(id: string): Observable<void> {
    return this.api.delete<void>(`${this.base}/transaction-types/${id}`);
  }

  // ── Dunning config (singleton) ──
  getDunning(): Observable<DunningConfig> {
    return this.api.get<DunningConfig>(`${this.base}/dunning`);
  }
  upsertDunning(body: DunningConfig): Observable<DunningConfig> {
    return this.api.put<DunningConfig>(`${this.base}/dunning`, body);
  }

  // ── Billing cycle config (singleton) ──
  getCycle(): Observable<BillingCycleConfig> {
    return this.api.get<BillingCycleConfig>(`${this.base}/cycle`);
  }
  upsertCycle(body: BillingCycleConfig): Observable<BillingCycleConfig> {
    return this.api.put<BillingCycleConfig>(`${this.base}/cycle`, body);
  }
}
