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
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../core/util/http-errors';

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

  form: UpsertEmailSenderPayload = {
    address: '',
    displayName: '',
    notes: '',
  };

  constructor(
    private senders: EmailSendersService,
    private route: ActivatedRoute,
    private router: Router,
    private toast: ToastService,
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
        this.toast.error(extractErrorMessage(err, 'Failed to load sender'));
        this.loading = false;
      },
    });
  }

  submit(): void {
    if (!this.form.address.trim()) {
      this.toast.warning('Address is required');
      return;
    }
    const payload: UpsertEmailSenderPayload = {
      address: this.form.address.trim(),
      displayName: this.form.displayName?.trim() || undefined,
      notes: this.form.notes?.trim() || undefined,
    };
    this.saving = true;
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
        this.toast.error(extractErrorMessage(err, 'Save failed'));
      },
    });
  }
}
