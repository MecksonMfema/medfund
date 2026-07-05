import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type LiaisonKind = 'MEMBER' | 'STAFF' | 'LIAISON';

export interface Group {
  id: string;
  name: string;
  registrationNumber?: string;
  address?: string;
  /**
   * Group contact email — fallback recipient for contribution statements
   * when no liaison user is assigned. Required for newly-created groups
   * (V038); optional here to remain tolerant of legacy rows.
   */
  email?: string;
  /** Discriminator for liaisonUserId. Null when no liaison is assigned. */
  liaisonKind?: LiaisonKind | null;
  /** ID in members, staff_users, or group_liaisons per liaisonKind. */
  liaisonUserId?: string | null;
  status: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface UpsertGroupPayload {
  name: string;
  /**
   * @deprecated The backend issues registration numbers automatically
   * per the tenant's configured shape (V125). Any value sent here is
   * ignored on create and rejected on update. Kept on the type only
   * for old serialised drafts; new forms no longer include it.
   */
  registrationNumber?: string;
  address?: string;
  /** Required on create; optional on update. */
  email?: string;
  /**
   * On create: 'MEMBER' | 'STAFF' | 'LIAISON' (or null for no liaison).
   * On update: 'CLEAR' to drop the existing liaison; null/undefined for
   * "no change"; otherwise the kind paired with liaisonUserId.
   */
  liaisonKind?: LiaisonKind | 'CLEAR' | null;
  liaisonUserId?: string | null;
}

@Injectable({ providedIn: 'root' })
export class GroupsService {
  constructor(private api: ApiService) {}

  list(): Observable<Group[]> {
    return this.api.get<Group[]>('/groups');
  }

  findById(id: string): Observable<Group> {
    return this.api.get<Group>(`/groups/${id}`);
  }

  search(q: string): Observable<Group[]> {
    return this.api.get<Group[]>('/groups/search', { q });
  }

  create(body: UpsertGroupPayload): Observable<Group> {
    return this.api.post<Group>('/groups', body);
  }

  update(id: string, body: UpsertGroupPayload): Observable<Group> {
    return this.api.put<Group>(`/groups/${id}`, body);
  }

  suspend(id: string): Observable<Group> {
    return this.api.post<Group>(`/groups/${id}/suspend`, {});
  }

  /**
   * Terminate a group with an optional effective date + reason.
   * Backend cascades member statuses on the same run.
   * Pass a null effectiveDate to default to today server-side.
   */
  terminate(id: string, effectiveDate: string | null, reason: string | null): Observable<Group> {
    const body: Record<string, string> = {};
    if (effectiveDate) body['effectiveDate'] = effectiveDate;
    if (reason)        body['reason']        = reason;
    return this.api.post<Group>(`/groups/${id}/actions/terminate`, body);
  }
}
