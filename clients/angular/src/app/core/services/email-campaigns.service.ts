import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

export type EmailCampaignStatus = 'draft' | 'sending' | 'sent' | 'failed';

export interface EmailCampaign {
  id: string;
  senderId?: string;
  subject: string;
  bodyHtml: string;
  bodyText?: string;
  audienceFilter: string;
  status: EmailCampaignStatus;
  scheduledFor?: string;
  sentAt?: string;
  recipientCount?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface UpsertEmailCampaignPayload {
  senderId?: string;
  subject: string;
  bodyHtml: string;
  bodyText?: string;
  audienceFilter?: string;
}

export interface AudiencePreview {
  count: number;
  sample: { memberNumber: string; firstName: string; lastName: string; email: string }[];
}

@Injectable({ providedIn: 'root' })
export class EmailCampaignsService {
  constructor(private api: ApiService) {}

  list(): Observable<EmailCampaign[]> {
    return this.api.get<EmailCampaign[]>('/email-campaigns');
  }

  findById(id: string): Observable<EmailCampaign> {
    return this.api.get<EmailCampaign>(`/email-campaigns/${id}`);
  }

  create(body: UpsertEmailCampaignPayload): Observable<EmailCampaign> {
    return this.api.post<EmailCampaign>('/email-campaigns', body);
  }

  update(id: string, body: UpsertEmailCampaignPayload): Observable<EmailCampaign> {
    return this.api.put<EmailCampaign>(`/email-campaigns/${id}`, body);
  }

  delete(id: string): Observable<void> {
    return this.api.delete<void>(`/email-campaigns/${id}`);
  }

  send(id: string): Observable<EmailCampaign> {
    return this.api.post<EmailCampaign>(`/email-campaigns/${id}/send`, {});
  }

  previewAudience(filterJson: string): Observable<AudiencePreview> {
    return this.api.post<AudiencePreview>('/email-campaigns/preview-audience', filterJson);
  }
}
