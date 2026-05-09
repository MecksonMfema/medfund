import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface WaitingPeriod {
  id: string;
  schemeId: string;
  conditionType: string;
  waitingDays: number;
  description?: string;
  createdAt?: string;
}

export interface UpsertWaitingPeriodPayload {
  schemeId: string;
  conditionType: string;
  waitingDays: number;
  description?: string;
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

@Injectable({ providedIn: 'root' })
export class WaitingPeriodService {
  constructor(private api: ApiService) {}

  // ── Initial waiting periods ──
  list(schemeId?: string): Observable<WaitingPeriod[]> {
    const params: Record<string, string> = {};
    if (schemeId) params['schemeId'] = schemeId;
    return this.api.get<WaitingPeriod[]>('/waiting-periods', params);
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
