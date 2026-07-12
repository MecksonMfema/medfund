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

  // ── ICD codes ──
  searchIcdCodes(q: string): Observable<IcdCode[]> {
    return this.api.get<IcdCode[]>('/icd-codes/search', { q });
  }

  // ── Rejection reasons ──
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
