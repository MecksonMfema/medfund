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
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../core/util/http-errors';

@Component({
  selector: 'app-campaign-composer',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent, SelectComponent],
  templateUrl: './campaign-composer.component.html',
  styleUrl: './campaign-composer.component.scss',
})
export class CampaignComposerComponent implements OnInit {
  campaignId: string | null = null;
  loading = false;
  saving = false;
  previewing = false;
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

  get senderOptions(): SelectOption[] {
    return this.senders.map(s => ({
      value: s.id,
      label: `${s.address}${s.displayName ? ' (' + s.displayName + ')' : ''}`,
    }));
  }

  constructor(
    private campaigns: EmailCampaignsService,
    private senderService: EmailSendersService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
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
        this.toast.error(extractErrorMessage(err, 'Failed to load composer'));
        this.loading = false;
      },
    });
  }

  previewAudience(): void {
    this.previewing = true;
    this.campaigns.previewAudience(this.form.audienceFilter || '{}').subscribe({
      next: (resp) => { this.preview = resp; this.previewing = false; },
      error: (err) => {
        this.toast.error(extractErrorMessage(err, 'Audience preview failed'));
        this.previewing = false;
      },
    });
  }

  saveDraft(): void {
    if (!this.validate()) return;
    this.saving = true;
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
        this.toast.error(extractErrorMessage(err, 'Save failed'));
      },
    });
  }

  saveAndSend(): void {
    if (!this.validate()) return;
    if (!confirm('Save the draft and send the campaign? Recipients will be computed from the audience filter.')) return;
    this.saving = true;
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
            this.toast.error(extractErrorMessage(err, 'Send failed'));
          },
        });
      },
      error: (err) => {
        this.saving = false;
        this.toast.error(extractErrorMessage(err, 'Save failed'));
      },
    });
  }

  private validate(): boolean {
    if (!this.form.subject.trim()) {
      this.toast.warning('Subject is required');
      return false;
    }
    if (!this.form.bodyHtml.trim()) {
      this.toast.warning('Body is required');
      return false;
    }
    try {
      JSON.parse(this.form.audienceFilter || '{}');
    } catch {
      this.toast.warning('Audience filter must be valid JSON');
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
