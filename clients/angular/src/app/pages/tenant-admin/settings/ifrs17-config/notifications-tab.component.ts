import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  TenantIfrs17NotificationConfigRow,
  TenantIfrs17NotificationConfigService,
  AddTenantIfrs17NotificationConfig,
  UpdateTenantIfrs17NotificationConfig,
  Ifrs17DeliveryMethod,
  Ifrs17EventType,
} from '../../../../core/services/tenant-ifrs17-notification-config.service';
import { TenantService } from '../../../../core/services/tenant.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';

interface EditableRow extends TenantIfrs17NotificationConfigRow {
  editing?: boolean;
  draftDeliveryMethod?: Ifrs17DeliveryMethod;
  draftThrottleMinutes?: number | null;
  draftIsActive?: boolean;
}

const EVENT_TYPE_OPTIONS: SelectOption[] = [
  { value: 'ONEROUS_TRANSITION',        label: 'Onerous transition' },
  { value: 'CSM_NEGATIVE',              label: 'CSM negative' },
  { value: 'LOCKED_IN_CURVE_FALLBACK',  label: 'Locked-in curve fallback' },
  { value: 'IBNR_SUB_JOB_STALE',        label: 'IBNR sub-job stale' },
  { value: 'OPENING_BALANCE_AUTO_DERIVED', label: 'Opening balance auto-derived' },
  { value: 'ALL',                       label: 'All event types' },
];

const DELIVERY_METHOD_OPTIONS: SelectOption[] = [
  { value: 'EMAIL',   label: 'Email' },
  { value: 'WEBHOOK', label: 'Webhook' },
  { value: 'BOTH',    label: 'Both' },
];

/**
 * Phase 15 §19 (I30) IFRS 17 material-event notification CRUD tab.
 * Sub-tab under IFRS 17 Config (settings). Rows key on (event_type,
 * recipient); the notification-service Go dispatcher (§20) reads active
 * rows at fan-out time. Recipient is an email address (EMAIL / BOTH) or a
 * webhook URL (WEBHOOK / BOTH); server-side validation enforces the
 * matching shape.
 */
@Component({
  selector: 'app-ifrs17-notifications-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SkeletonComponent, SelectComponent],
  templateUrl: './notifications-tab.component.html',
  styleUrl: '../actuarial-bases/actuarial-bases.component.scss',
})
export class NotificationsTabComponent implements OnInit {
  rows: EditableRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;
  pendingId: string | null = null;

  addingOpen = false;
  adding = false;
  newRow: AddTenantIfrs17NotificationConfig = this.blankNewRow();

  readonly eventTypeOptions = EVENT_TYPE_OPTIONS;
  readonly deliveryMethodOptions = DELIVERY_METHOD_OPTIONS;

  constructor(
    private service: TenantIfrs17NotificationConfigService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) {
      this.errorMessage = 'No active tenant context';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.service.list(tenantId).subscribe({
      next: (rows) => {
        this.rows = rows.map(r => ({ ...r }));
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || err?.message || 'Failed to load notification config rows';
        this.loading = false;
      },
    });
  }

  openAdd(): void {
    this.addingOpen = true;
    this.newRow = this.blankNewRow();
  }

  cancelAdd(): void {
    this.addingOpen = false;
    this.newRow = this.blankNewRow();
  }

  add(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    if (!this.newRow.eventType || !this.newRow.deliveryMethod || !this.newRow.recipient?.trim()) {
      this.errorMessage = 'Event type, delivery method, and recipient are required';
      return;
    }
    this.adding = true;
    this.errorMessage = null;
    this.service.add(tenantId, this.trimAddPayload(this.newRow)).subscribe({
      next: () => {
        this.adding = false;
        this.addingOpen = false;
        this.successMessage = `Added ${this.newRow.eventType} → ${this.newRow.recipient}`;
        setTimeout(() => (this.successMessage = null), 3000);
        this.newRow = this.blankNewRow();
        this.refresh();
      },
      error: (err) => {
        this.adding = false;
        this.errorMessage = err?.error?.detail || 'Failed to add notification config';
      },
    });
  }

  startEdit(row: EditableRow): void {
    row.editing = true;
    row.draftDeliveryMethod = row.deliveryMethod;
    row.draftThrottleMinutes = row.throttleMinutes;
    row.draftIsActive = row.isActive;
  }

  cancelEdit(row: EditableRow): void {
    row.editing = false;
    row.draftDeliveryMethod = undefined;
    row.draftThrottleMinutes = undefined;
    row.draftIsActive = undefined;
  }

  saveEdit(row: EditableRow): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    if (!row.draftDeliveryMethod) return;
    const payload: UpdateTenantIfrs17NotificationConfig = {
      deliveryMethod: row.draftDeliveryMethod,
      throttleMinutes: row.draftThrottleMinutes ?? row.throttleMinutes,
      isActive: row.draftIsActive ?? row.isActive,
    };
    this.pendingId = row.id;
    this.service.update(tenantId, row.id, payload).subscribe({
      next: (updated) => {
        Object.assign(row, updated, { editing: false });
        row.draftDeliveryMethod = undefined;
        row.draftThrottleMinutes = undefined;
        row.draftIsActive = undefined;
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to update notification config';
        this.pendingId = null;
      },
    });
  }

  remove(row: EditableRow): void {
    if (!confirm(`Delete notification for ${row.eventType} → ${row.recipient}?`)) return;
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.pendingId = row.id;
    this.service.delete(tenantId, row.id).subscribe({
      next: () => {
        this.rows = this.rows.filter(r => r.id !== row.id);
        this.pendingId = null;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Failed to delete notification config';
        this.pendingId = null;
      },
    });
  }

  private blankNewRow(): AddTenantIfrs17NotificationConfig {
    return {
      eventType: 'ONEROUS_TRANSITION' as Ifrs17EventType,
      deliveryMethod: 'EMAIL' as Ifrs17DeliveryMethod,
      recipient: '',
      throttleMinutes: 15,
      isActive: true,
    };
  }

  private trimAddPayload(p: AddTenantIfrs17NotificationConfig): AddTenantIfrs17NotificationConfig {
    return {
      eventType: p.eventType,
      deliveryMethod: p.deliveryMethod,
      recipient: (p.recipient ?? '').trim(),
      throttleMinutes: p.throttleMinutes ?? 15,
      isActive: p.isActive ?? true,
    };
  }
}
