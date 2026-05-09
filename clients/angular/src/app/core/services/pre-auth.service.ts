import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type PreAuthStatus = 'pending' | 'approved' | 'rejected' | 'expired';

export interface PreAuthorization {
  id: string;
  authNumber: string;
  memberId: string;
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
  providerId: string;
  schemeId: string;
  tariffCode: string;
  diagnosisCode?: string;
  requestedAmount: string;
  currencyCode: string;
  notes?: string;
}

@Injectable({ providedIn: 'root' })
export class PreAuthService {
  constructor(private api: ApiService) {}

  list(): Observable<PreAuthorization[]> {
    return this.api.get<PreAuthorization[]>('/pre-authorizations');
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
