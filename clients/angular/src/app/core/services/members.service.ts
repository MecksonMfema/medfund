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

  /** Soft-remove a dependant. Backend flips status to 'removed'. */
  removeDependant(id: string): Observable<Dependant> {
    return this.api.post<Dependant>(`/dependants/${id}/remove`, {});
  }
}
