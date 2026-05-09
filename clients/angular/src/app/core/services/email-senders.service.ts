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

@Injectable({ providedIn: 'root' })
export class EmailSendersService {
  constructor(private api: ApiService) {}

  list(): Observable<EmailSender[]> {
    return this.api.get<EmailSender[]>('/email-senders');
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
