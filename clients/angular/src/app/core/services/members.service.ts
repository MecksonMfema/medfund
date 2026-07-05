import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { CursorPage } from './admin.service';

export interface Member {
  id: string;
  memberNumber: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  email: string;
  phone: string;
  status: string;
  groupId: string | null;
  schemeId: string | null;
  enrollmentDate: string;
  createdAt: string;
  /**
   * Per-member custom premium (Phase A — V030). Non-null when an
   * operator has set a personal price that overrides the scheme's
   * age-group default. Only honoured when the tenant's pricingModel
   * is 'INDIVIDUAL'. Cleared via /members/{id}/clear-billing-override.
   */
  billingOverrideAmount?: number | null;
  billingOverrideReason?: string | null;
  billingOverrideEffectiveFrom?: string | null;
}

export interface Dependant {
  id: string;
  memberId: string;
  firstName: string;
  lastName: string;
  dateOfBirth?: string;
  gender?: string;
  relationship: string;
  nationalId?: string;
  status: string;
  /**
   * Effective date the dependant became a beneficiary (V047). Always
   * the 1st of a month. Absent on legacy rows that pre-date V047 (the
   * migration backfills so this is only null for freshly-created rows
   * still waiting on the server response).
   */
  enrollmentDate?: string;
  createdAt?: string;
  updatedAt?: string;
}

@Injectable({ providedIn: 'root' })
export class MembersService {
  constructor(private api: ApiService) {}

  getPage(opts: { q?: string; status?: string; cursor?: string; limit?: number } = {}): Observable<CursorPage<Member>> {
    const params: Record<string, string> = {};
    if (opts.q)      params['q']      = opts.q;
    if (opts.status) params['status'] = opts.status;
    if (opts.cursor) params['cursor'] = opts.cursor;
    if (opts.limit)  params['limit']  = String(opts.limit);
    return this.api.get<CursorPage<Member>>('/members', params);
  }

  getById(id: string): Observable<Member> {
    return this.api.get<Member>(`/members/${id}`);
  }

  searchByName(q: string): Observable<Member[]> {
    return this.api.get<Member[]>('/members/search', { q });
  }

  enroll(data: any): Observable<Member> {
    return this.api.post<Member>('/members', data);
  }

  update(id: string, data: any): Observable<Member> {
    return this.api.put<Member>(`/members/${id}`, data);
  }

  activate(id: string): Observable<Member> {
    return this.api.post<Member>(`/members/${id}/activate`, {});
  }

  suspend(id: string): Observable<Member> {
    return this.api.post<Member>(`/members/${id}/suspend`, {});
  }

  terminate(id: string): Observable<Member> {
    return this.api.post<Member>(`/members/${id}/terminate`, {});
  }

  clearBillingOverride(id: string): Observable<Member> {
    return this.api.post<Member>(`/members/${id}/clear-billing-override`, {});
  }

  /** All members belonging to a group — used by GroupDetailComponent. */
  getByGroupId(groupId: string): Observable<Member[]> {
    return this.api.get<Member[]>(`/members/group/${groupId}`);
  }

  getDependants(memberId: string): Observable<Dependant[]> {
    return this.api.get<Dependant[]>(`/dependants/member/${memberId}`);
  }

  addDependant(data: any): Observable<Dependant> {
    return this.api.post<Dependant>('/dependants', data);
  }

  updateDependant(id: string, data: any): Observable<Dependant> {
    return this.api.put<Dependant>(`/dependants/${id}`, data);
  }

  /** Deactivate a dependant with an effective date (V046). Dependants
   *  are never hard-deleted — this is the terminal soft-transition.
   *  Backend bills up to and including the cycle that contains the
   *  effective date; from the next cycle the resolver drops them off.
   *  Pass null to default to today. */
  deactivateDependant(id: string, effectiveDate: string | null): Observable<Dependant> {
    const body = effectiveDate ? { effectiveDate } : {};
    return this.api.post<Dependant>(`/dependants/${id}/deactivate`, body);
  }
}
