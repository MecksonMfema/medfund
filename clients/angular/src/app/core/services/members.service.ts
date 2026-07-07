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

  /**
   * Book a group change (V048). Back-dated effectiveDate applies
   * immediately and posts arrears/rebate on the group ledger;
   * forward-dated stashes a PENDING row for approval.
   */
  requestGroupChange(memberId: string,
                     payload: { targetGroupId: string; effectiveDate: string; reason?: string }
                    ): Observable<GroupChangeResponse> {
    return this.api.post<GroupChangeResponse>(
      `/members/${memberId}/group-changes`, payload);
  }

  listGroupChanges(memberId: string): Observable<GroupChangeResponse[]> {
    return this.api.get<GroupChangeResponse[]>(`/members/${memberId}/group-changes`);
  }

  approveGroupChange(memberId: string, id: string): Observable<GroupChangeResponse> {
    return this.api.post<GroupChangeResponse>(
      `/members/${memberId}/group-changes/${id}/approve`, {});
  }

  rejectGroupChange(memberId: string, id: string, reason: string): Observable<GroupChangeResponse> {
    return this.api.post<GroupChangeResponse>(
      `/members/${memberId}/group-changes/${id}/reject`, { reason });
  }

  /**
   * Book a role-swap between a member and one of their dependants
   * (V048). Same PENDING/APPLIED semantics as group change. On apply
   * the dependant is promoted, the old principal is demoted, sibling
   * dependants re-parent onto the new principal, and both old rows
   * are marked status='swapped' with the swapped_to_id pointer set.
   */
  requestSwap(memberId: string,
              payload: { dependantId: string; effectiveDate: string; reason?: string }
             ): Observable<MemberSwapResponse> {
    return this.api.post<MemberSwapResponse>(`/members/${memberId}/swaps`, payload);
  }

  listSwaps(memberId: string): Observable<MemberSwapResponse[]> {
    return this.api.get<MemberSwapResponse[]>(`/members/${memberId}/swaps`);
  }

  approveSwap(memberId: string, id: string): Observable<MemberSwapResponse> {
    return this.api.post<MemberSwapResponse>(`/members/${memberId}/swaps/${id}/approve`, {});
  }

  rejectSwap(memberId: string, id: string, reason: string): Observable<MemberSwapResponse> {
    return this.api.post<MemberSwapResponse>(`/members/${memberId}/swaps/${id}/reject`, { reason });
  }

  /**
   * Book a scheme change (contributions-service). The kind is
   * auto-classified server-side by comparing scheme currencies and
   * benefit annual_limit sums; waiting-period rules may push the
   * effective date forward. Back-dated dates apply immediately and
   * post arrears/rebate on the member's ledger.
   */
  requestSchemeChange(payload: {
    memberId: string;
    fromSchemeId: string;
    toSchemeId: string;
    effectiveDate: string;
    reason?: string;
    changeKind?: string;
  }): Observable<SchemeChangeResponse> {
    return this.api.post<SchemeChangeResponse>('/scheme-changes', payload);
  }

  listSchemeChanges(memberId: string): Observable<SchemeChangeResponse[]> {
    return this.api.get<SchemeChangeResponse[]>(`/scheme-changes/member/${memberId}`);
  }

  approveSchemeChange(id: string): Observable<SchemeChangeResponse> {
    return this.api.post<SchemeChangeResponse>(`/scheme-changes/${id}/approve`, {});
  }

  rejectSchemeChange(id: string, reason: string): Observable<SchemeChangeResponse> {
    return this.api.post<SchemeChangeResponse>(
      `/scheme-changes/${id}/reject?reason=${encodeURIComponent(reason)}`, {});
  }

  applySchemeChange(id: string): Observable<SchemeChangeResponse> {
    return this.api.post<SchemeChangeResponse>(`/scheme-changes/${id}/apply`, {});
  }
}

/**
 * V048 group-change response. `backdated=true` means the flip
 * happened immediately and arrears/rebate is being posted
 * asynchronously by contributions-service; forward-dated requests
 * stay `PENDING` until the executor promotes them on the effective
 * date.
 */
export interface GroupChangeResponse {
  id: string;
  memberId: string;
  fromGroupId: string | null;
  toGroupId: string;
  status: string;
  requestedDate: string;
  effectiveDate: string;
  reason?: string | null;
  rejectionReason?: string | null;
  appliedAt?: string | null;
  backdated: boolean;
}

/** V048 swap response. Once applied, `newMemberId` points at the
 *  promoted member's row and `oldDependantId` at the demoted
 *  principal's new dependant row. Historical claims/contributions
 *  still point at the original member; the members.swapped_to_id
 *  pointer lets reporting follow the redirect. */
export interface MemberSwapResponse {
  id: string;
  oldMemberId: string;
  dependantId: string;
  newMemberId: string | null;
  oldDependantId: string | null;
  status: string;
  requestedDate: string;
  effectiveDate: string;
  reason?: string | null;
  rejectionReason?: string | null;
  appliedAt?: string | null;
}

/**
 * Scheme-change response. `status` runs PENDING → APPROVED → EFFECTIVE
 * (plus REJECTED / CANCELLED). `changeKind` is one of UPGRADE,
 * DOWNGRADE, CURRENCY_CHANGE, or CROSS_GRADE and routes which ledger
 * adjustment SchemeChangedConsumer posts when the row applies.
 */
export interface SchemeChangeResponse {
  id: string;
  memberId: string;
  fromSchemeId: string;
  toSchemeId: string;
  status: string;
  requestedDate: string;
  effectiveDate: string;
  reason?: string | null;
  rejectionReason?: string | null;
  changeKind?: string | null;
  approvedBy?: string | null;
  approvedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}
