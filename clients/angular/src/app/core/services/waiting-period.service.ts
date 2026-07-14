import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface WaitingPeriod {
  id: string;
  schemeId: string;
  conditionType: string;
  waitingDays: number;
  description?: string;
  /** V052 age-band scope. Null = universal. When set, this rule applies only
   *  to members whose age at claim time falls in [minAge, maxAge]. */
  minAge?: number;
  maxAge?: number;
  createdAt?: string;
}

export interface UpsertWaitingPeriodPayload {
  schemeId: string;
  conditionType: string;
  waitingDays: number;
  description?: string;
  minAge?: number;
  maxAge?: number;
}

export interface SchemeChangeWaitingPeriod {
  id: string;
  changeType: 'UPGRADE' | 'DOWNGRADE';
  benefitType?: string;
  waitingDays: number;
  description?: string;
  isActive: boolean;
  updatedAt?: string;
}

export interface UpsertSchemeChangeWaitingPeriodPayload {
  changeType: 'UPGRADE' | 'DOWNGRADE';
  benefitType?: string;
  waitingDays: number;
  description?: string;
  isActive?: boolean;
}

/**
 * Envelope shape mirrored by every server-side paginated endpoint on
 * the platform.
 */
export interface WaitingPeriodPageResponse<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

/** Row returned by GET /waiting-periods/page — scheme name pre-joined. */
export interface WaitingPeriodRow extends WaitingPeriod {
  schemeName?: string;
}

export interface WaitingPeriodPageParams {
  schemeId?: string;
  conditionType?: string;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

export interface SchemeChangeWaitingPeriodPageParams {
  changeType?: 'UPGRADE' | 'DOWNGRADE';
  benefitType?: string;
  activeOnly?: boolean;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class WaitingPeriodService {
  constructor(private api: ApiService) {}

  // ── Initial waiting periods ──
  list(schemeId?: string): Observable<WaitingPeriod[]> {
    const params: Record<string, string> = {};
    if (schemeId) params['schemeId'] = schemeId;
    return this.api.get<WaitingPeriod[]>('/waiting-periods', params);
  }

  /**
   * Server-side paginated waiting-periods list. Feeds
   * /tenant/billing/waiting-periods. Scheme name pre-joined.
   */
  listPaged(opts: WaitingPeriodPageParams): Observable<WaitingPeriodPageResponse<WaitingPeriodRow>> {
    const params: Record<string, string> = {};
    if (opts.schemeId)      params['schemeId']      = opts.schemeId;
    if (opts.conditionType) params['conditionType'] = opts.conditionType;
    if (opts.q)             params['q']             = opts.q;
    if (opts.sortKey)       params['sortKey']       = opts.sortKey;
    if (opts.sortDirection) params['sortDirection'] = opts.sortDirection;
    if (opts.page !== undefined) params['page']     = String(opts.page);
    if (opts.size !== undefined) params['size']     = String(opts.size);
    return this.api.get<WaitingPeriodPageResponse<WaitingPeriodRow>>('/waiting-periods/page', params);
  }

  create(body: UpsertWaitingPeriodPayload): Observable<WaitingPeriod> {
    return this.api.post<WaitingPeriod>('/waiting-periods', body);
  }

  update(id: string, body: UpsertWaitingPeriodPayload): Observable<WaitingPeriod> {
    return this.api.put<WaitingPeriod>(`/waiting-periods/${id}`, body);
  }

  delete(id: string): Observable<void> {
    return this.api.delete<void>(`/waiting-periods/${id}`);
  }

  // ── Scheme-change waiting periods ──
  listSchemeChange(): Observable<SchemeChangeWaitingPeriod[]> {
    return this.api.get<SchemeChangeWaitingPeriod[]>('/scheme-change-waiting-periods');
  }

  /**
   * Server-side paginated scheme-change waiting-periods list. Feeds
   * /tenant/billing/scheme-change-waiting-periods.
   */
  listSchemeChangePaged(opts: SchemeChangeWaitingPeriodPageParams):
      Observable<WaitingPeriodPageResponse<SchemeChangeWaitingPeriod>> {
    const params: Record<string, string> = {};
    if (opts.changeType)     params['changeType']     = opts.changeType;
    if (opts.benefitType)    params['benefitType']    = opts.benefitType;
    if (opts.activeOnly !== undefined) params['activeOnly'] = String(opts.activeOnly);
    if (opts.q)              params['q']              = opts.q;
    if (opts.sortKey)        params['sortKey']        = opts.sortKey;
    if (opts.sortDirection)  params['sortDirection']  = opts.sortDirection;
    if (opts.page !== undefined) params['page']       = String(opts.page);
    if (opts.size !== undefined) params['size']       = String(opts.size);
    return this.api.get<WaitingPeriodPageResponse<SchemeChangeWaitingPeriod>>('/scheme-change-waiting-periods/page', params);
  }

  createSchemeChange(body: UpsertSchemeChangeWaitingPeriodPayload): Observable<SchemeChangeWaitingPeriod> {
    return this.api.post<SchemeChangeWaitingPeriod>('/scheme-change-waiting-periods', body);
  }

  updateSchemeChange(id: string, body: UpsertSchemeChangeWaitingPeriodPayload): Observable<SchemeChangeWaitingPeriod> {
    return this.api.put<SchemeChangeWaitingPeriod>(`/scheme-change-waiting-periods/${id}`, body);
  }

  deleteSchemeChange(id: string): Observable<void> {
    return this.api.delete<void>(`/scheme-change-waiting-periods/${id}`);
  }
}
