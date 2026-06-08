import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export interface GroupLiaison {
  id: string;
  firstName: string;
  lastName: string;
  email: string;
  phone?: string;
  address?: string;
  status: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateGroupLiaisonPayload {
  firstName: string;
  lastName: string;
  email: string;
  phone?: string;
  address?: string;
}

@Injectable({ providedIn: 'root' })
export class GroupLiaisonsService {
  constructor(private api: ApiService) {}

  search(q: string, limit = 10): Observable<GroupLiaison[]> {
    return this.api.get<GroupLiaison[]>('/group-liaisons', { q, limit: String(limit) });
  }

  getById(id: string): Observable<GroupLiaison> {
    return this.api.get<GroupLiaison>(`/group-liaisons/${id}`);
  }

  create(payload: CreateGroupLiaisonPayload): Observable<GroupLiaison> {
    return this.api.post<GroupLiaison>('/group-liaisons', payload);
  }
}
