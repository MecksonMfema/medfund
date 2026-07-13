import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

// ── Tariff schedules ────────────────────────────────────────────────────
export interface TariffSchedule {
  id: string;
  name: string;
  effectiveDate: string;
  endDate?: string;
  source?: string;
  status: string;
  createdAt?: string;
}

export interface CreateTariffSchedulePayload {
  name: string;
  effectiveDate: string;
  endDate?: string;
  source?: string;
}

// ── Tariff codes ────────────────────────────────────────────────────────
export interface TariffCode {
  id: string;
  scheduleId: string;
  code: string;
  description: string;
  /** Legacy free-text category label — kept for backwards compat on the
   *  read side; the authoritative link is {@link categoryId}. */
  category?: string;
  /** V063 — mandatory FK to the tariff_categories catalogue. */
  categoryId?: string;
  unitPrice: string;
  currencyCode?: string;
  requiresPreAuth?: boolean;
  createdAt?: string;
}

/**
 * Row shape returned by {@code GET /tariffs/codes/page}. The category
 * label is pre-joined server-side so the table renders each row without
 * a client-side categories-catalogue lookup — the AHFOZ March seed puts
 * ~5,000 codes on a single schedule and the previous unpaginated flow
 * was noticeably slow.
 */
export interface TariffCodeRow {
  id: string;
  scheduleId: string;
  code: string;
  description: string;
  categoryId?: string;
  categoryLabel?: string;
  unitPrice: string;
  currencyCode?: string;
  requiresPreAuth?: boolean;
}

export interface PageResponse<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface TariffCodePageParams {
  scheduleId?: string;
  q?: string;
  categoryId?: string;
  requiresPreAuth?: boolean;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

export interface CreateTariffCodePayload {
  scheduleId: string;
  code: string;
  description: string;
  /** V063 — mandatory. */
  categoryId: string;
  unitPrice: string;
  currencyCode?: string;
  requiresPreAuth?: boolean;
}

// ── Tariff modifiers (read-only catalogue) ──────────────────────────────
export interface TariffModifier {
  id: string;
  code: string;
  name: string;
  description?: string;
  adjustmentType: 'PERCENTAGE' | 'FIXED' | 'MULTIPLIER';
  adjustmentValue: string;
  isActive: boolean;
}

// ── ICD codes ───────────────────────────────────────────────────────────
export interface IcdCode {
  id: string;
  code: string;
  description: string;
  category?: string;
  chapter?: string;
  isActive?: boolean;
}

// ── Rejection reasons ───────────────────────────────────────────────────
export interface RejectionReason {
  id: string;
  code: string;
  description: string;
  category?: string;
  isActive: boolean;
}

export interface UpsertRejectionReasonPayload {
  code: string;
  description: string;
  category?: string;
  isActive?: boolean;
}

@Injectable({ providedIn: 'root' })
export class ClaimsConfigService {
  constructor(private api: ApiService) {}

  // ── Tariff schedules ──
  listSchedules(): Observable<TariffSchedule[]> {
    return this.api.get<TariffSchedule[]>('/tariffs/schedules');
  }

  getSchedule(id: string): Observable<TariffSchedule> {
    return this.api.get<TariffSchedule>(`/tariffs/schedules/${id}`);
  }

  createSchedule(body: CreateTariffSchedulePayload): Observable<TariffSchedule> {
    return this.api.post<TariffSchedule>('/tariffs/schedules', body);
  }

  // ── Tariff codes ──
  listCodesBySchedule(scheduleId: string): Observable<TariffCode[]> {
    return this.api.get<TariffCode[]>(`/tariffs/codes/schedule/${scheduleId}`);
  }

  /**
   * Server-side paginated tariff-codes list. Feeds the codes table on
   * /tenant/claims/tariffs/:scheduleId/codes. Preferred over
   * {@link listCodesBySchedule} — the AHFOZ March schedule alone carries
   * ~5,000 codes and hydrating the whole set in one call is what made the
   * previous page slow.
   */
  listCodesPaged(opts: TariffCodePageParams): Observable<PageResponse<TariffCodeRow>> {
    const params: Record<string, string> = {};
    if (opts.scheduleId)                 params['scheduleId']       = opts.scheduleId;
    if (opts.q)                          params['q']                = opts.q;
    if (opts.categoryId)                 params['categoryId']       = opts.categoryId;
    if (opts.requiresPreAuth !== undefined) params['requiresPreAuth'] = String(opts.requiresPreAuth);
    if (opts.sortKey)                    params['sortKey']          = opts.sortKey;
    if (opts.sortDirection)              params['sortDirection']    = opts.sortDirection;
    if (opts.page !== undefined)         params['page']             = String(opts.page);
    if (opts.size !== undefined)         params['size']             = String(opts.size);
    return this.api.get<PageResponse<TariffCodeRow>>('/tariffs/codes/page', params);
  }

  searchCodes(q: string): Observable<TariffCode[]> {
    return this.api.get<TariffCode[]>('/tariffs/codes/search', { q });
  }

  createCode(body: CreateTariffCodePayload): Observable<TariffCode> {
    return this.api.post<TariffCode>('/tariffs/codes', body);
  }

  // ── Tariff modifiers ──
  listModifiers(): Observable<TariffModifier[]> {
    return this.api.get<TariffModifier[]>('/tariffs/modifiers');
  }

  /**
   * Server-side paginated tariff-modifiers list. Feeds
   * /tenant/claims/modifiers.
   */
  listModifiersPaged(opts: {
    activeOnly?: boolean;
    adjustmentType?: string;
    q?: string;
    sortKey?: string;
    sortDirection?: 'asc' | 'desc';
    page?: number;
    size?: number;
  }): Observable<PageResponse<TariffModifier>> {
    const params: Record<string, string> = {};
    if (opts.activeOnly !== undefined) params['activeOnly']     = String(opts.activeOnly);
    if (opts.adjustmentType)           params['adjustmentType'] = opts.adjustmentType;
    if (opts.q)                        params['q']              = opts.q;
    if (opts.sortKey)                  params['sortKey']        = opts.sortKey;
    if (opts.sortDirection)            params['sortDirection']  = opts.sortDirection;
    if (opts.page !== undefined)       params['page']           = String(opts.page);
    if (opts.size !== undefined)       params['size']           = String(opts.size);
    return this.api.get<PageResponse<TariffModifier>>('/tariffs/modifiers/page', params);
  }

  // ── ICD codes ──
  searchIcdCodes(q: string): Observable<IcdCode[]> {
    return this.api.get<IcdCode[]>('/icd-codes/search', { q });
  }

  // ── Rejection reasons ──
  listRejectionReasonsPaged(opts: {
    activeOnly?: boolean;
    category?: string;
    q?: string;
    sortKey?: string;
    sortDirection?: 'asc' | 'desc';
    page?: number;
    size?: number;
  }): Observable<PageResponse<RejectionReason>> {
    const params: Record<string, string> = {};
    if (opts.activeOnly !== undefined) params['activeOnly']    = String(opts.activeOnly);
    if (opts.category)                 params['category']      = opts.category;
    if (opts.q)                        params['q']             = opts.q;
    if (opts.sortKey)                  params['sortKey']       = opts.sortKey;
    if (opts.sortDirection)            params['sortDirection'] = opts.sortDirection;
    if (opts.page !== undefined)       params['page']          = String(opts.page);
    if (opts.size !== undefined)       params['size']          = String(opts.size);
    return this.api.get<PageResponse<RejectionReason>>('/rejection-reasons/page', params);
  }

  listRejectionReasons(activeOnly = false): Observable<RejectionReason[]> {
    const params: Record<string, string> = {};
    if (activeOnly) params['activeOnly'] = 'true';
    return this.api.get<RejectionReason[]>('/rejection-reasons', params);
  }

  createRejectionReason(body: UpsertRejectionReasonPayload): Observable<RejectionReason> {
    return this.api.post<RejectionReason>('/rejection-reasons', body);
  }

  updateRejectionReason(id: string, body: UpsertRejectionReasonPayload): Observable<RejectionReason> {
    return this.api.put<RejectionReason>(`/rejection-reasons/${id}`, body);
  }

  deleteRejectionReason(id: string): Observable<void> {
    return this.api.delete<void>(`/rejection-reasons/${id}`);
  }
}
