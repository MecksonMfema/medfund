import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type EmailSenderStatus = 'pending' | 'verified' | 'revoked';

export interface EmailSender {
  id: string;
  address: string;
  displayName?: string;
  status: EmailSenderStatus;
  verifiedAt?: string;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface UpsertEmailSenderPayload {
  address: string;
  displayName?: string;
  notes?: string;
}

export interface EmailSendersPageResponse<T> {
  content: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface EmailSenderPageParams {
  status?: EmailSenderStatus;
  q?: string;
  sortKey?: string;
  sortDirection?: 'asc' | 'desc';
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class EmailSendersService {
  constructor(private api: ApiService) {}

  list(): Observable<EmailSender[]> {
    return this.api.get<EmailSender[]>('/email-senders');
  }

  listPaged(opts: EmailSenderPageParams = {}): Observable<EmailSendersPageResponse<EmailSender>> {
    const params: Record<string, string> = {
      page: String(opts.page ?? 0),
      size: String(opts.size ?? 50),
    };
    if (opts.status)         params['status']        = opts.status;
    if (opts.q)              params['q']             = opts.q;
    if (opts.sortKey)        params['sortKey']       = opts.sortKey;
    if (opts.sortDirection)  params['sortDirection'] = opts.sortDirection;
    return this.api.get<EmailSendersPageResponse<EmailSender>>('/email-senders/page', params);
  }

  findById(id: string): Observable<EmailSender> {
    return this.api.get<EmailSender>(`/email-senders/${id}`);
  }

  create(body: UpsertEmailSenderPayload): Observable<EmailSender> {
    return this.api.post<EmailSender>('/email-senders', body);
  }

  update(id: string, body: UpsertEmailSenderPayload): Observable<EmailSender> {
    return this.api.put<EmailSender>(`/email-senders/${id}`, body);
  }

  verify(id: string): Observable<EmailSender> {
    return this.api.post<EmailSender>(`/email-senders/${id}/verify`, {});
  }

  revoke(id: string): Observable<EmailSender> {
    return this.api.post<EmailSender>(`/email-senders/${id}/revoke`, {});
  }

  delete(id: string): Observable<void> {
    return this.api.delete<void>(`/email-senders/${id}`);
  }
}
