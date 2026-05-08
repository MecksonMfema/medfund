import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface Scheme {
  id: string;
  name: string;
  description?: string;
  schemeType?: string;
  status: string;
  effectiveDate: string;
  endDate?: string;
  currencyCode?: string;
}

export interface AgeGroup {
  id: string;
  schemeId: string;
  name: string;
  minAge: number;
  maxAge: number;
  contributionAmount: string;
  currencyCode: string;
}

export interface GroupOption {
  id: string;
  name: string;
  registrationNumber?: string;
  contactEmail?: string;
}

export interface SchemeBenefit {
  id: string;
  schemeId: string;
  name: string;
  benefitType: string;
  annualLimit?: string;
  dailyLimit?: string;
  eventLimit?: string;
  currencyCode: string;
  waitingPeriodDays?: number;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface UpsertBenefitPayload {
  schemeId?: string;
  name: string;
  benefitType: string;
  annualLimit?: string;
  dailyLimit?: string;
  eventLimit?: string;
  currencyCode?: string;
  waitingPeriodDays?: number;
  description?: string;
}

export interface Contribution {
  id: string;
  memberId: string;
  groupId: string | null;
  schemeId: string;
  amount: number;
  currencyCode: string;
  periodStart: string;
  periodEnd: string;
  status: string;
}

export interface Transaction {
  id: string;
  transactionNumber: string;
  contributionId?: string;
  invoiceId?: string;
  amount: string;
  currencyCode: string;
  transactionType: string;
  paymentMethod?: string;
  reference?: string;
  status: string;
  transactionDate?: string;
  createdAt: string;
}

export interface Invoice {
  id: string;
  invoiceNumber: string;
  groupId: string | null;
  totalAmount: number;
  currencyCode: string;
  status: string;
  dueDate: string;
}

export interface UpsertSchemePayload {
  name: string;
  description?: string;
  schemeType?: string;
  effectiveDate: string;
  endDate?: string;
  currencyCode?: string;
}

export interface UpsertAgeGroupPayload {
  schemeId: string;
  name: string;
  minAge: number;
  maxAge: number;
  contributionAmount: string;
  currencyCode?: string;
}

export interface BillingFilterPayload {
  periodStart: string;
  periodEnd: string;
  schemeIds?: string[];
  groupIds?: string[];
  memberIds?: string[];
}

export interface BillingPreviewSampleRow {
  memberId: string;
  memberNumber: string;
  schemeId: string;
  schemeName: string;
  groupId: string | null;
  amount: string;
  currencyCode: string;
}

export interface BillingPreviewResponse {
  totalRows: number;
  totalsByCurrency: Record<string, string>;
  sample: BillingPreviewSampleRow[];
  cooldownActive: boolean;
  cooldownRemainingMinutes: number | null;
}

export interface BillingCommitResponse {
  contributionsCreated: number;
  totalsByCurrency: Record<string, string>;
  committedAt: string;
}

export interface RecordTransactionPayload {
  contributionId?: string;
  invoiceId?: string;
  amount: string;
  currencyCode: string;
  transactionType: string;
  paymentMethod?: string;
  reference?: string;
}

@Injectable({ providedIn: 'root' })
export class ContributionsService {
  constructor(private api: ApiService) {}

  // ── Schemes ──
  getSchemes(): Observable<Scheme[]> {
    return this.api.get<Scheme[]>('/schemes');
  }

  getSchemeById(id: string): Observable<Scheme> {
    return this.api.get<Scheme>(`/schemes/${id}`);
  }

  createScheme(data: UpsertSchemePayload): Observable<Scheme> {
    return this.api.post<Scheme>('/schemes', data);
  }

  updateScheme(id: string, data: UpsertSchemePayload): Observable<Scheme> {
    return this.api.put<Scheme>(`/schemes/${id}`, data);
  }

  // ── Groups (read-only autocomplete) ──
  searchGroups(q: string, limit = 20): Observable<GroupOption[]> {
    const params: Record<string, string> = { limit: String(limit) };
    if (q) params['q'] = q;
    return this.api.get<GroupOption[]>('/billing/groups/search', params);
  }

  // ── Age groups ──
  getAgeGroupsByScheme(schemeId: string): Observable<AgeGroup[]> {
    return this.api.get<AgeGroup[]>(`/schemes/${schemeId}/age-groups`);
  }

  createAgeGroup(data: UpsertAgeGroupPayload): Observable<AgeGroup> {
    return this.api.post<AgeGroup>('/schemes/age-groups', data);
  }

  // ── Scheme benefits ──
  getBenefitsByScheme(schemeId: string): Observable<SchemeBenefit[]> {
    return this.api.get<SchemeBenefit[]>(`/schemes/${schemeId}/benefits`);
  }

  getBenefitById(id: string): Observable<SchemeBenefit> {
    return this.api.get<SchemeBenefit>(`/schemes/benefits/${id}`);
  }

  createBenefit(data: UpsertBenefitPayload): Observable<SchemeBenefit> {
    return this.api.post<SchemeBenefit>('/schemes/benefits', data);
  }

  updateBenefit(id: string, data: UpsertBenefitPayload): Observable<SchemeBenefit> {
    return this.api.put<SchemeBenefit>(`/schemes/benefits/${id}`, data);
  }

  deleteBenefit(id: string): Observable<void> {
    return this.api.delete<void>(`/schemes/benefits/${id}`);
  }

  // ── Contributions ──
  getContributionsByMember(memberId: string): Observable<Contribution[]> {
    return this.api.get<Contribution[]>(`/contributions/member/${memberId}`);
  }

  getContributionsByGroup(groupId: string): Observable<Contribution[]> {
    return this.api.get<Contribution[]>(`/contributions/group/${groupId}`);
  }

  getContributionsByStatus(status: string): Observable<Contribution[]> {
    return this.api.get<Contribution[]>(`/contributions/status/${status}`);
  }

  previewBilling(filters: BillingFilterPayload): Observable<BillingPreviewResponse> {
    return this.api.post<BillingPreviewResponse>('/contributions/preview', filters);
  }

  commitBilling(filters: BillingFilterPayload): Observable<BillingCommitResponse> {
    return this.api.post<BillingCommitResponse>('/contributions/commit', filters);
  }

  // ── Transactions ──
  recordTransaction(data: RecordTransactionPayload): Observable<Transaction> {
    return this.api.post<Transaction>('/transactions', data);
  }

  getTransactionsByContribution(contributionId: string): Observable<Transaction[]> {
    return this.api.get<Transaction[]>(`/transactions/contribution/${contributionId}`);
  }

  // ── Invoices ──
  getInvoicesByGroup(groupId: string): Observable<Invoice[]> {
    return this.api.get<Invoice[]>(`/invoices/group/${groupId}`);
  }

  getInvoicesByStatus(status: string): Observable<Invoice[]> {
    return this.api.get<Invoice[]>(`/invoices/status/${status}`);
  }
}
