import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface Group {
  id: string;
  name: string;
  registrationNumber?: string;
  contactPerson?: string;
  contactEmail?: string;
  contactPhone?: string;
  address?: string;
  status: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface UpsertGroupPayload {
  name: string;
  registrationNumber?: string;
  contactPerson?: string;
  contactEmail?: string;
  contactPhone?: string;
  address?: string;
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
}
