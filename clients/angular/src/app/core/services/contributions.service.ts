import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface Scheme {
  id: string;
  name: string;
  description?: string;
  schemeType?: string;
  /** Insurance product line this scheme belongs to (HEALTH, LIFE, VEHICLE, …).
   *  Drives whether Age Groups / Scheme Benefits screens apply. Backed by the
   *  schemes.insurance_line column (NOT NULL, default HEALTH). */
  insuranceLine?: string;
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
  status?: string;
}

export interface GroupOption {
  id: string;
  name: string;
  registrationNumber?: string;
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
  status?: string;
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
  /** Derived from schemeType via LINE_FOR_SCHEME_TYPE on the form. The form
   *  always sets this; the backend defaults to HEALTH if omitted. */
  insuranceLine?: string;
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
  /** Aggregate invoices the commit would create (one per group + currency). */
  groupInvoicesProjected: number;
  /** Per-member invoices the commit would create. */
  individualInvoicesProjected: number;
  /** Tenant's current membership model (INDIVIDUAL_ONLY / GROUP_ONLY / BOTH). */
  membershipModel: string;
}

export interface BillingCommitResponse {
  contributionsCreated: number;
  totalsByCurrency: Record<string, string>;
  committedAt: string;
  groupInvoicesCreated: number;
  individualInvoicesCreated: number;
  membershipModel: string;
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

export interface TransactionSearchParams {
  currency?: string;
  transactionType?: string;
  paymentMethod?: string;
  periodStart?: string;  // YYYY-MM-DD
  periodEnd?: string;    // YYYY-MM-DD
  contributionId?: string;
  invoiceId?: string;
  q?: string;
  page?: number;
  size?: number;
}

export interface TransactionsPage {
  content: Transaction[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
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

  /** Free-text scheme search — feeds the operational scheme picker. */
  searchSchemes(q: string, limit = 10): Observable<Scheme[]> {
    return this.api.get<Scheme[]>('/schemes/search', { q, limit: String(limit) });
  }

  createScheme(data: UpsertSchemePayload): Observable<Scheme> {
    return this.api.post<Scheme>('/schemes', data);
  }

  updateScheme(id: string, data: UpsertSchemePayload): Observable<Scheme> {
    return this.api.put<Scheme>(`/schemes/${id}`, data);
  }

  /** Soft-delete: flips the scheme's status to 'inactive'. */
  deactivateScheme(id: string): Observable<Scheme> {
    return this.api.post<Scheme>(`/schemes/${id}/deactivate`, {});
  }

  /** Re-enables a previously-deactivated scheme. */
  activateScheme(id: string): Observable<Scheme> {
    return this.api.post<Scheme>(`/schemes/${id}/activate`, {});
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

  getAgeGroupById(id: string): Observable<AgeGroup> {
    return this.api.get<AgeGroup>(`/schemes/age-groups/${id}`);
  }

  updateAgeGroup(id: string, data: UpsertAgeGroupPayload): Observable<AgeGroup> {
    return this.api.put<AgeGroup>(`/schemes/age-groups/${id}`, data);
  }

  /** Soft-delete: flips the age group's status to 'inactive'. */
  deactivateAgeGroup(id: string): Observable<AgeGroup> {
    return this.api.post<AgeGroup>(`/schemes/age-groups/${id}/deactivate`, {});
  }

  /** Re-enables a previously-deactivated age group. */
  activateAgeGroup(id: string): Observable<AgeGroup> {
    return this.api.post<AgeGroup>(`/schemes/age-groups/${id}/activate`, {});
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

  /** Soft-delete: flips the benefit's status to 'inactive'. Hard-delete
   *  is no longer supported — see the controller comment in SchemeController. */
  deactivateBenefit(id: string): Observable<SchemeBenefit> {
    return this.api.post<SchemeBenefit>(`/schemes/benefits/${id}/deactivate`, {});
  }

  /** Re-enables a previously-deactivated benefit. */
  activateBenefit(id: string): Observable<SchemeBenefit> {
    return this.api.post<SchemeBenefit>(`/schemes/benefits/${id}/activate`, {});
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
  searchTransactions(params: TransactionSearchParams = {}): Observable<TransactionsPage> {
    const q: Record<string, string> = {};
    if (params.currency)        q['currency'] = params.currency;
    if (params.transactionType) q['transactionType'] = params.transactionType;
    if (params.paymentMethod)   q['paymentMethod'] = params.paymentMethod;
    if (params.periodStart)     q['periodStart'] = params.periodStart;
    if (params.periodEnd)       q['periodEnd'] = params.periodEnd;
    if (params.contributionId)  q['contributionId'] = params.contributionId;
    if (params.invoiceId)       q['invoiceId'] = params.invoiceId;
    if (params.q)               q['q'] = params.q;
    if (params.page !== undefined) q['page'] = String(params.page);
    if (params.size !== undefined) q['size'] = String(params.size);
    return this.api.get<TransactionsPage>('/transactions', q);
  }

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
