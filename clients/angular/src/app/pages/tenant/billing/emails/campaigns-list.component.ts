import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  EmailCampaign,
  EmailCampaignsService,
} from '../../../../core/services/email-campaigns.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-campaigns-list',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, SkeletonComponent, HumanizePipe],
  templateUrl: './campaigns-list.component.html',
  styleUrl: './campaigns-list.component.scss',
})
export class CampaignsListComponent implements OnInit {
  rows: EmailCampaign[] = [];
  loading = false;
  errorMessage: string | null = null;
  pendingId: string | null = null;

  constructor(private campaigns: EmailCampaignsService, private router: Router) {}

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading = true;
    this.campaigns.list().subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load campaigns';
        this.loading = false;
      },
    });
  }

  edit(c: EmailCampaign): void {
    this.router.navigate(['/tenant/billing/emails/send'], { queryParams: { id: c.id } });
  }

  send(c: EmailCampaign): void {
    if (!confirm(`Send "${c.subject}" to all members matching the audience filter? This will compute the recipient count and stamp sent_at.`)) return;
    this.pendingId = c.id;
    this.campaigns.send(c.id).subscribe({
      next: (updated) => {
        this.rows = this.rows.map(r => r.id === updated.id ? updated : r);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Send failed';
        this.pendingId = null;
      },
    });
  }

  remove(c: EmailCampaign): void {
    if (!confirm(`Delete campaign "${c.subject}"?`)) return;
    this.pendingId = c.id;
    this.campaigns.delete(c.id).subscribe({
      next: () => {
        this.rows = this.rows.filter(r => r.id !== c.id);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Delete failed';
        this.pendingId = null;
      },
    });
  }
}
