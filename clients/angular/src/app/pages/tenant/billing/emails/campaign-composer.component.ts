import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import {
  AudiencePreview,
  EmailCampaign,
  EmailCampaignsService,
  UpsertEmailCampaignPayload,
} from '../../../../core/services/email-campaigns.service';
import {
  EmailSender,
  EmailSendersService,
} from '../../../../core/services/email-senders.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-campaign-composer',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './campaign-composer.component.html',
  styleUrl: './campaign-composer.component.scss',
})
export class CampaignComposerComponent implements OnInit {
  campaignId: string | null = null;
  loading = false;
  saving = false;
  previewing = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  senders: EmailSender[] = [];
  preview: AudiencePreview | null = null;
  readOnly = false;

  form: UpsertEmailCampaignPayload & { audienceFilter: string } = {
    senderId: '',
    subject: '',
    bodyHtml: '',
    bodyText: '',
    audienceFilter: '{}',
  };

  constructor(
    private campaigns: EmailCampaignsService,
    private senderService: EmailSendersService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.campaignId = this.route.snapshot.queryParamMap.get('id');
    this.loading = true;
    forkJoin({
      senders: this.senderService.list(),
      existing: this.campaignId ? this.campaigns.findById(this.campaignId) : of<EmailCampaign | null>(null),
    }).subscribe({
      next: ({ senders, existing }) => {
        this.senders = senders.filter(s => s.status === 'verified');
        if (existing) {
          this.readOnly = existing.status !== 'draft';
          this.form = {
            senderId: existing.senderId ?? '',
            subject: existing.subject,
            bodyHtml: existing.bodyHtml,
            bodyText: existing.bodyText ?? '',
            audienceFilter: existing.audienceFilter || '{}',
          };
        }
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load composer';
        this.loading = false;
      },
    });
  }

  previewAudience(): void {
    this.previewing = true;
    this.errorMessage = null;
    this.campaigns.previewAudience(this.form.audienceFilter || '{}').subscribe({
      next: (resp) => { this.preview = resp; this.previewing = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Audience preview failed';
        this.previewing = false;
      },
    });
  }

  saveDraft(): void {
    if (!this.validate()) return;
    this.saving = true;
    this.errorMessage = null;
    const payload = this.toPayload();
    const stream = this.campaignId
      ? this.campaigns.update(this.campaignId, payload)
      : this.campaigns.create(payload);
    stream.subscribe({
      next: (saved) => {
        this.saving = false;
        this.successMessage = 'Draft saved';
        this.campaignId = saved.id;
        setTimeout(() => this.successMessage = null, 1500);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }

  saveAndSend(): void {
    if (!this.validate()) return;
    if (!confirm('Save the draft and send the campaign? Recipients will be computed from the audience filter.')) return;
    this.saving = true;
    this.errorMessage = null;
    const payload = this.toPayload();
    const persist = this.campaignId
      ? this.campaigns.update(this.campaignId, payload)
      : this.campaigns.create(payload);
    persist.subscribe({
      next: (saved) => {
        this.campaignId = saved.id;
        this.campaigns.send(saved.id).subscribe({
          next: () => {
            this.saving = false;
            this.successMessage = 'Campaign sent';
            setTimeout(() => this.router.navigate(['/tenant/billing/emails']), 800);
          },
          error: (err) => {
            this.saving = false;
            this.errorMessage = err?.error?.detail || 'Send failed';
          },
        });
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || 'Save failed';
      },
    });
  }

  private validate(): boolean {
    if (!this.form.subject.trim()) {
      this.errorMessage = 'Subject is required';
      return false;
    }
    if (!this.form.bodyHtml.trim()) {
      this.errorMessage = 'Body is required';
      return false;
    }
    try {
      JSON.parse(this.form.audienceFilter || '{}');
    } catch {
      this.errorMessage = 'Audience filter must be valid JSON';
      return false;
    }
    return true;
  }

  private toPayload(): UpsertEmailCampaignPayload {
    return {
      senderId: this.form.senderId || undefined,
      subject: this.form.subject.trim(),
      bodyHtml: this.form.bodyHtml,
      bodyText: this.form.bodyText?.trim() || undefined,
      audienceFilter: this.form.audienceFilter || '{}',
    };
  }
}
