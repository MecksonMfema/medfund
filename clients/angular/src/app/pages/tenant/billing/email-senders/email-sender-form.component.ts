import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  EmailSender,
  EmailSendersService,
  UpsertEmailSenderPayload,
} from '../../../../core/services/email-senders.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-email-sender-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './email-sender-form.component.html',
  styleUrl: './email-sender-form.component.scss',
})
export class EmailSenderFormComponent implements OnInit {
  senderId: string | null = null;
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  form: UpsertEmailSenderPayload = {
    address: '',
    displayName: '',
    notes: '',
  };

  constructor(
    private senders: EmailSendersService,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.senderId = this.route.snapshot.paramMap.get('id');
    if (!this.senderId) return;
    this.loading = true;
    this.senders.findById(this.senderId).subscribe({
      next: (s: EmailSender) => {
        this.form = {
          address: s.address,
          displayName: s.displayName ?? '',
          notes: s.notes ?? '',
        };
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to load sender';
        this.loading = false;
      },
    });
  }

  submit(): void {
    if (!this.form.address.trim()) {
      this.errorMessage = 'Address is required';
      return;
    }
    const payload: UpsertEmailSenderPayload = {
      address: this.form.address.trim(),
      displayName: this.form.displayName?.trim() || undefined,
      notes: this.form.notes?.trim() || undefined,
    };
    this.saving = true;
    this.errorMessage = null;
    const stream = this.senderId
      ? this.senders.update(this.senderId, payload)
      : this.senders.create(payload);
    stream.subscribe({
      next: () => {
        this.saving = false;
        this.router.navigate(['/tenant/billing/email-senders']);
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Save failed';
      },
    });
  }
}
