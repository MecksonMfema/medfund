import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type ClaimStatus =
  | 'SUBMITTED' | 'VERIFIED' | 'IN_ADJUDICATION'
  | 'ADJUDICATED' | 'REJECTED' | 'PENDING_INFO'
  | 'COMMITTED' | 'PAID' | 'CANCELLED';

export interface Claim {
  id: string;
  claimNumber: string;
  memberId: string;
  dependantId?: string;
  providerId: string;
  schemeId: string;
  benefitId?: string;
  claimType: string;
  status: ClaimStatus;
  serviceDate: string;
  submissionDate?: string;
  claimedAmount: string;
  approvedAmount?: string;
  paidAmount?: string;
  currencyCode: string;
  diagnosisCodes?: string;
  procedureCodes?: string;
  notes?: string;
  rejectionReason?: string;
  rejectionNotes?: string;
  verificationCode?: string;
  verifiedAt?: string;
  adjudicatedAt?: string;
  adjudicatedBy?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface ClaimLine {
  id: string;
  claimId: string;
  tariffCode: string;
  description?: string;
  quantity: number;
  unitPrice: string;
  claimedAmount: string;
  approvedAmount?: string;
  modifierCodes?: string;
  currencyCode?: string;
}

export interface ClaimLinePayload {
  tariffCode: string;
  description?: string;
  quantity: number;
  unitPrice: string;
  claimedAmount: string;
  modifierCodes?: string;
  currencyCode?: string;
}

export interface SubmitClaimPayload {
  memberId: string;
  dependantId?: string;
  providerId: string;
  schemeId: string;
  benefitId?: string;
  claimType?: string;
  serviceDate: string;
  claimedAmount: string;
  currencyCode: string;
  diagnosisCodes?: string;
  procedureCodes?: string;
  notes?: string;
  lines?: ClaimLinePayload[];
}

@Injectable({ providedIn: 'root' })
export class ClaimsService {
  constructor(private api: ApiService) {}

  list(): Observable<Claim[]> {
    return this.api.get<Claim[]>('/claims');
  }

  getById(id: string): Observable<Claim> {
    return this.api.get<Claim>(`/claims/${id}`);
  }

  getByStatus(status: string): Observable<Claim[]> {
    return this.api.get<Claim[]>(`/claims/status/${status}`);
  }

  getByMember(memberId: string): Observable<Claim[]> {
    return this.api.get<Claim[]>(`/claims/member/${memberId}`);
  }

  getByProvider(providerId: string): Observable<Claim[]> {
    return this.api.get<Claim[]>(`/claims/provider/${providerId}`);
  }

  getLines(claimId: string): Observable<ClaimLine[]> {
    return this.api.get<ClaimLine[]>(`/claims/${claimId}/lines`);
  }

  submit(body: SubmitClaimPayload): Observable<Claim> {
    return this.api.post<Claim>('/claims', body);
  }

  verify(id: string, verificationCode: string): Observable<Claim> {
    return this.api.post<Claim>(
      `/claims/${id}/verify?verificationCode=${encodeURIComponent(verificationCode)}`,
      {});
  }

  adjudicate(id: string): Observable<Claim> {
    return this.api.post<Claim>(`/claims/${id}/adjudicate`, {});
  }

  updateStatus(id: string, status: ClaimStatus): Observable<Claim> {
    return this.api.post<Claim>(`/claims/${id}/status?newStatus=${status}`, {});
  }
}
