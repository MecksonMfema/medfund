import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  EmailSender,
  EmailSendersService,
} from '../../../../core/services/email-senders.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { HumanizePipe } from '../../../../shared/pipes/humanize.pipe';

@Component({
  selector: 'app-email-senders-list',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent, SkeletonComponent, HumanizePipe],
  templateUrl: './email-senders-list.component.html',
  styleUrl: './email-senders-list.component.scss',
})
export class EmailSendersListComponent implements OnInit {
  rows: EmailSender[] = [];
  loading = false;
  errorMessage: string | null = null;
  pendingId: string | null = null;

  constructor(private senders: EmailSendersService, private router: Router) {}

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.loading = true;
    this.senders.list().subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load email senders';
        this.loading = false;
      },
    });
  }

  edit(s: EmailSender): void {
    this.router.navigate(['/tenant/billing/email-senders', s.id, 'edit']);
  }

  verify(s: EmailSender): void {
    if (!confirm(`Mark ${s.address} as verified? Confirm DNS / domain checks have been completed out-of-band.`)) return;
    this.pendingId = s.id;
    this.senders.verify(s.id).subscribe({
      next: (updated) => {
        this.rows = this.rows.map(r => r.id === updated.id ? updated : r);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Verify failed';
        this.pendingId = null;
      },
    });
  }

  revoke(s: EmailSender): void {
    if (!confirm(`Revoke ${s.address}? Future campaigns will not be able to send from this address.`)) return;
    this.pendingId = s.id;
    this.senders.revoke(s.id).subscribe({
      next: (updated) => {
        this.rows = this.rows.map(r => r.id === updated.id ? updated : r);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Revoke failed';
        this.pendingId = null;
      },
    });
  }
}
