import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface MemberBalance {
  memberId: string;
  currencyCode: string;
  balance: string;
  lastChargeAt?: string;
  lastPaymentAt?: string;
  updatedAt?: string;
}

export interface GroupBalance {
  groupId: string;
  currencyCode: string;
  balance: string;
  lastChargeAt?: string;
  lastPaymentAt?: string;
  updatedAt?: string;
}

export interface DebtorRow {
  subjectType: 'MEMBER' | 'GROUP';
  subjectId: string;
  subjectCode?: string;
  subjectName?: string;
  subjectEmail?: string;
  currencyCode: string;
  balance: string;
  lastChargeAt?: string;
  lastPaymentAt?: string;
  daysSinceLastActivity?: number;
}

export interface BadDebtRow extends DebtorRow {
  agingStatus: 'GRACE' | 'SUSPENDED' | 'WRITE_OFF' | 'AGED';
}

export interface PageResponse<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface FlagBadDebtPayload {
  contributionId: string;
  reason?: string;
}

@Injectable({ providedIn: 'root' })
export class BalanceService {
  private base = '/billing/balances';

  constructor(private api: ApiService) {}

  getMemberBalance(memberId: string, currency: string): Observable<MemberBalance> {
    return this.api.get<MemberBalance>(`${this.base}/members/${memberId}`, { currency });
  }

  getGroupBalance(groupId: string, currency: string): Observable<GroupBalance> {
    return this.api.get<GroupBalance>(`${this.base}/groups/${groupId}`, { currency });
  }

  /**
   * List currently-billable subjects with an outstanding balance (debtors).
   *
   * @param subjectType {@code "MEMBER"} → only ungrouped individuals;
   *                    {@code "GROUP"} → only groups; omit to return
   *                    both (default). Members attached to a group are
   *                    filtered out server-side because their balance
   *                    rolls up to the group liaison.
   */
  listDebtors(
    currency: string,
    subjectType?: 'MEMBER' | 'GROUP',
    q?: string,
    page = 0,
    size = 20,
  ): Observable<PageResponse<DebtorRow>> {
    const params: Record<string, string> = { currency, page: String(page), size: String(size) };
    if (subjectType) params['subjectType'] = subjectType;
    if (q) params['q'] = q;
    return this.api.get<PageResponse<DebtorRow>>(`${this.base}/debtors`, params);
  }

  /**
   * Download the current debtors filter as an .xlsx workbook. Same
   * filters as {@link listDebtors} — the backend applies them, so
   * whatever tabs / search the operator has active on the page is
   * exactly what lands in the sheet.
   */
  exportDebtorsExcel(
    currency: string,
    subjectType?: 'MEMBER' | 'GROUP',
    q?: string,
  ): Observable<Blob> {
    const params: Record<string, string> = { currency };
    if (subjectType) params['subjectType'] = subjectType;
    if (q) params['q'] = q;
    return this.api.getBlob(`${this.base}/debtors/export/excel`, params);
  }

  /**
   * List subjects that have already fallen off the billing roster
   * (status IN deactivated/terminated) but still owe money — the
   * "bad-debts" view. Same row shape and paging as
   * {@link listDebtors}; the difference is a server-side filter on
   * subject status + `balance > 0`.
   */
  listBadDebts(
    currency: string,
    subjectType?: 'MEMBER' | 'GROUP',
    q?: string,
    page = 0,
    size = 20,
  ): Observable<PageResponse<DebtorRow>> {
    const params: Record<string, string> = { currency, page: String(page), size: String(size) };
    if (subjectType) params['subjectType'] = subjectType;
    if (q) params['q'] = q;
    return this.api.get<PageResponse<DebtorRow>>(`${this.base}/bad-debts`, params);
  }

  /**
   * XLSX download for the bad-debts list. Same filters as
   * {@link listBadDebts}; the backend applies them so the workbook
   * mirrors what's on screen.
   */
  exportBadDebtsExcel(
    currency: string,
    subjectType?: 'MEMBER' | 'GROUP',
    q?: string,
  ): Observable<Blob> {
    const params: Record<string, string> = { currency };
    if (subjectType) params['subjectType'] = subjectType;
    if (q) params['q'] = q;
    return this.api.getBlob(`${this.base}/bad-debts/export/excel`, params);
  }

  /**
   * Aged-balances view — currently-billable subjects whose oldest
   * unpaid contribution is older than the tenant's grace/suspension
   * threshold. Each row carries a {@code GRACE / SUSPENDED / WRITE_OFF}
   * classification. Distinct from {@link listBadDebts}, which is the
   * post-deactivation "money we may never collect" view.
   */
  listAged(currency: string, minAgeDays?: number, q?: string, page = 0, size = 20): Observable<PageResponse<BadDebtRow>> {
    const params: Record<string, string> = { currency, page: String(page), size: String(size) };
    if (minAgeDays !== undefined && minAgeDays !== null) params['minAgeDays'] = String(minAgeDays);
    if (q) params['q'] = q;
    return this.api.get<PageResponse<BadDebtRow>>(`${this.base}/aged-balances`, params);
  }

  flagBadDebt(body: FlagBadDebtPayload): Observable<unknown> {
    return this.api.post<unknown>(`${this.base}/bad-debts/flag`, body);
  }
}
