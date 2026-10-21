import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { FinancePageResponse } from './finance.service';

/**
 * Row shape for the V078 member-liability list. Mirrors the Java DTO
 * {@code MemberCostShareLiabilityRow}.
 */
export interface MemberLiabilityRow {
  id: string;
  memberId: string;
  memberName: string | null;
  memberNumber: string | null;
  claimId: string;
  claimNumber: string | null;
  totalOwed: string;
  totalSettled: string;
  currencyCode: string;
  currencyCodeOriginal: string | null;
  status: 'OPEN' | 'PARTIALLY_SETTLED' | 'SETTLED' | 'WRITTEN_OFF';
  createdAt: string;
  updatedAt: string;
}

export interface MemberLiabilitySettlement {
  id: string;
  receiptTransactionId: string | null;
  amount: string;
  currencyCode: string;
  source: 'MEMBER_PAYMENT' | 'MEMBER_PAID_PROVIDER' | 'WRITE_OFF';
  settledAt: string;
}

export interface MemberLiabilityDetail {
  id: string;
  memberId: string;
  claimId: string;
  claimNumber: string | null;
  deductible: string;
  copay: string;
  coinsurance: string;
  shortfall: string;
  notCovered: string;
  totalOwed: string;
  totalSettled: string;
  currencyCode: string;
  currencyCodeOriginal: string | null;
  status: 'OPEN' | 'PARTIALLY_SETTLED' | 'SETTLED' | 'WRITTEN_OFF';
  createdAt: string;
  updatedAt: string;
  settlements: MemberLiabilitySettlement[];
}

export interface MemberLiabilityPageParams {
  status?: string;
  currencyCode?: string;
  memberId?: string;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class MemberLiabilityService {
  constructor(private api: ApiService) {}

  listPaged(opts: MemberLiabilityPageParams = {}): Observable<FinancePageResponse<MemberLiabilityRow>> {
    const params: Record<string, string> = {};
    if (opts.status)         params['status']         = opts.status;
    if (opts.currencyCode)   params['currencyCode']   = opts.currencyCode;
    if (opts.memberId)       params['memberId']       = opts.memberId;
    if (opts.q)              params['q']              = opts.q;
    if (opts.sortKey)        params['sortKey']        = opts.sortKey;
    if (opts.sortDirection)  params['sortDirection']  = opts.sortDirection;
    if (opts.page !== undefined) params['page'] = String(opts.page);
    if (opts.size !== undefined) params['size'] = String(opts.size);
    return this.api.get<FinancePageResponse<MemberLiabilityRow>>('/member-cost-share-liabilities', params);
  }

  getById(id: string): Observable<MemberLiabilityDetail> {
    return this.api.get<MemberLiabilityDetail>(`/member-cost-share-liabilities/${id}`);
  }
}
