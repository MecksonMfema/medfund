import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type PreAuthStatus = 'pending' | 'approved' | 'rejected' | 'expired';

export interface PreAuthorization {
  id: string;
  authNumber: string;
  /** Sponsoring member id — always set. For a dependant pre-auth this is
   *  the dependant's sponsor. */
  memberId: string;
  /** Present only for a dependant pre-auth; identifies the covered dependant. */
  dependantId?: string | null;
  providerId: string;
  schemeId?: string;
  tariffCode: string;
  diagnosisCode?: string;
  status: PreAuthStatus;
  requestedAmount: string;
  approvedAmount?: string;
  currencyCode: string;
  requestedDate: string;
  decisionDate?: string;
  expiryDate?: string;
  decisionBy?: string;
  notes?: string;
  rejectionReason?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface PreAuthRequestPayload {
  memberId: string;
  dependantId?: string | null;
  providerId: string;
  schemeId: string;
  tariffCode: string;
  diagnosisCode?: string;
  requestedAmount: string;
  currencyCode: string;
  notes?: string;
}

export interface PreAuthorizationRow {
  id: string;
  authNumber: string;
  memberId: string;
  memberName?: string;
  memberNumber?: string;
  dependantId?: string;
  providerId?: string;
  providerName?: string;
  schemeId?: string;
  tariffCode: string;
  diagnosisCode?: string;
  status: PreAuthStatus;
  requestedAmount: string;
  approvedAmount?: string;
  currencyCode: string;
  requestedDate: string;
  decisionDate?: string;
  expiryDate?: string;
  createdAt: string;
}

export interface PreAuthPageResponse {
  content: PreAuthorizationRow[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface PreAuthPageParams {
  status?: string;
  memberId?: string;
  providerId?: string;
  schemeId?: string;
  tariffCode?: string;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class PreAuthService {
  constructor(private api: ApiService) {}

  list(): Observable<PreAuthorization[]> {
    return this.api.get<PreAuthorization[]>('/pre-authorizations');
  }

  /**
   * Server-side paginated pre-authorizations list. Feeds
   * /tenant/claims/preauth. Member + provider names pre-joined.
   */
  listPaged(opts: PreAuthPageParams): Observable<PreAuthPageResponse> {
    const params: Record<string, string> = {};
    if (opts.status)         params['status']         = opts.status;
    if (opts.memberId)       params['memberId']       = opts.memberId;
    if (opts.providerId)     params['providerId']     = opts.providerId;
    if (opts.schemeId)       params['schemeId']       = opts.schemeId;
    if (opts.tariffCode)     params['tariffCode']     = opts.tariffCode;
    if (opts.q)              params['q']              = opts.q;
    if (opts.sortKey)        params['sortKey']        = opts.sortKey;
    if (opts.sortDirection)  params['sortDirection']  = opts.sortDirection;
    if (opts.page !== undefined) params['page']       = String(opts.page);
    if (opts.size !== undefined) params['size']       = String(opts.size);
    return this.api.get<PreAuthPageResponse>('/pre-authorizations/page', params);
  }

  findById(id: string): Observable<PreAuthorization> {
    return this.api.get<PreAuthorization>(`/pre-authorizations/${id}`);
  }

  findByMember(memberId: string): Observable<PreAuthorization[]> {
    return this.api.get<PreAuthorization[]>(`/pre-authorizations/member/${memberId}`);
  }

  findByNumber(authNumber: string): Observable<PreAuthorization> {
    return this.api.get<PreAuthorization>(`/pre-authorizations/number/${authNumber}`);
  }

  create(body: PreAuthRequestPayload): Observable<PreAuthorization> {
    return this.api.post<PreAuthorization>('/pre-authorizations', body);
  }

  approve(id: string, approvedAmount: string, expiryDate: string): Observable<PreAuthorization> {
    const params = new URLSearchParams({ approvedAmount, expiryDate });
    return this.api.post<PreAuthorization>(`/pre-authorizations/${id}/approve?${params.toString()}`, {});
  }

  reject(id: string, rejectionReason: string): Observable<PreAuthorization> {
    const params = new URLSearchParams({ rejectionReason });
    return this.api.post<PreAuthorization>(`/pre-authorizations/${id}/reject?${params.toString()}`, {});
  }
}
