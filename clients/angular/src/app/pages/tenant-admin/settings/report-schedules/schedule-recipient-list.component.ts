import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import {
  AddRecipientRequest,
  TenantReportScheduleRecipient,
  TenantReportScheduleService,
} from '../../../../core/services/tenant-report-schedule.service';
import { TenantService } from '../../../../core/services/tenant.service';

/**
 * Editor for a single schedule's recipient list. Add / toggle-active /
 * delete. Every mutation flows through {@link TenantReportScheduleService}
 * which emits an audit event server-side. Emits `changed` after any
 * successful mutation so the parent can refresh its own snapshot.
 */
@Component({
  selector: 'app-schedule-recipient-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './schedule-recipient-list.component.html',
  styleUrl: './schedule-recipient-list.component.scss',
})
export class ScheduleRecipientListComponent {
  @Input({ required: true }) scheduleId!: string;
  @Input() recipients: TenantReportScheduleRecipient[] = [];
  @Output() changed = new EventEmitter<void>();

  draft: AddRecipientRequest = { email: '', displayName: '', isActive: true };
  saving = false;

  constructor(
    private service: TenantReportScheduleService,
    private tenant: TenantService,
    private toast: ToastService,
    private confirm: ConfirmService,
  ) {}

  add(form: NgForm): void {
    const tenantId = this.tenant.getTenantId();
    if (!tenantId) return;
    if (form.invalid || !this.draft.email.trim()) return;
    this.saving = true;
    this.service.addRecipient(tenantId, this.scheduleId, {
      email: this.draft.email.trim(),
      displayName: (this.draft.displayName ?? '').trim() || null,
      isActive: this.draft.isActive ?? true,
    }).subscribe({
      next: () => {
        this.saving = false;
        this.draft = { email: '', displayName: '', isActive: true };
        form.resetForm({ isActive: true });
        this.toast.success('Recipient added');
        this.changed.emit();
      },
      error: (err) => {
        this.saving = false;
        this.toast.error(err?.error?.detail || 'Could not add recipient');
      },
    });
  }

  toggleActive(row: TenantReportScheduleRecipient): void {
    const tenantId = this.tenant.getTenantId();
    if (!tenantId) return;
    const nextActive = !row.isActive;
    this.service.updateRecipient(tenantId, this.scheduleId, row.id, {
      isActive: nextActive,
    }).subscribe({
      next: () => {
        row.isActive = nextActive;
        this.toast.success(nextActive ? 'Recipient reactivated' : 'Recipient paused');
        this.changed.emit();
      },
      error: (err) => this.toast.error(err?.error?.detail || 'Could not update recipient'),
    });
  }

  async remove(row: TenantReportScheduleRecipient): Promise<void> {
    const tenantId = this.tenant.getTenantId();
    if (!tenantId) return;
    const ok = await this.confirm.ask({
      title: `Remove ${row.email}?`,
      message: 'The recipient will stop receiving scheduled deliveries. '
             + 'To re-add later you\'ll need their email again.',
      confirmLabel: 'Remove',
      danger: true,
    });
    if (!ok) return;
    this.service.deleteRecipient(tenantId, this.scheduleId, row.id).subscribe({
      next: () => {
        this.toast.success('Recipient removed');
        this.changed.emit();
      },
      error: (err) => this.toast.error(err?.error?.detail || 'Could not remove recipient'),
    });
  }
}
