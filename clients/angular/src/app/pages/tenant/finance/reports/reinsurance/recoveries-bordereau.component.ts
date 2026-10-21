import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  BordereauQuarterParams,
  MarkRecoveryReceivedPayload,
  RecoveriesBordereauRow,
  ReinsuranceService,
  Reinsurer,
  Treaty,
  WriteOffRecoveryPayload,
} from '../../../../../core/services/reinsurance.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { ReportBackButtonComponent } from '../shared/report-back-button.component';
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { composeWarningsToast, extractErrorMessage } from '../../../../../core/util/http-errors';

/**
 * Recoveries bordereau — one row per (recovery, participant) for the
 * selected quarter, showing status transitions (EXPECTED → INVOICED →
 * RECEIVED / WRITTEN_OFF). Export flips EXPECTED → INVOICED after the
 * response body is delivered (R8, controller side).
 */
@Component({
  selector: 'app-recoveries-bordereau',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, ReportBackButtonComponent],
  templateUrl: './recoveries-bordereau.component.html',
  styleUrls: ['../receipts/receipts-report.component.scss', './recoveries-bordereau.component.scss'],
})
export class RecoveriesBordereauComponent implements OnInit {
  loading = false;
  exporting = false;

  envelope: ReportResponse<RecoveriesBordereauRow[]> | null = null;

  currencies: TenantCurrencyConfig[] = [];
  reinsurers: Reinsurer[] = [];
  treaties: Treaty[] = [];

  year    = new Date().getUTCFullYear();
  quarter = currentQuarter();
  reinsurerId = '';
  treatyId    = '';
  reportingCurrency = '';

  // Phase 8 — recovery lifecycle modals. Row-level actions open one of
  // these two inline forms; submitting closes the form and re-fetches.
  markReceivedTargetId: string | null = null;
  markReceivedTargetLabel = '';
  markReceivedAmount: number | null = null;
  markReceivedAt = '';
  markReceivedSubmitting = false;

  writeOffTargetId: string | null = null;
  writeOffTargetLabel = '';
  writeOffReason = '';
  writeOffSubmitting = false;

  actionInProgress: Record<string, boolean> = {};

  constructor(
    private svc: ReinsuranceService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.loadCurrencies();
    this.loadReinsurers();
    this.loadTreaties();
    this.fetch();
  }

  private loadCurrencies(): void {
    const tenantId = this.tenantService.getTenantId();
    if (!tenantId) return;
    this.currencyService.listForTenant(tenantId).subscribe({
      next: cs => {
        this.currencies = cs.filter(c => c.isActive);
        const def = this.currencies.find(c => c.isDefault);
        if (def && !this.reportingCurrency) this.reportingCurrency = def.currencyCode;
      },
      error: () => { /* non-fatal */ },
    });
  }

  private loadReinsurers(): void {
    this.svc.listReinsurers(0, 200, true).subscribe({
      next: page => { this.reinsurers = page.content; },
      error: () => { /* non-fatal */ },
    });
  }

  private loadTreaties(): void {
    this.svc.listTreaties(0, 200).subscribe({
      next: page => { this.treaties = page.content; },
      error: () => { /* non-fatal */ },
    });
  }

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'Tenant default' },
      ...this.currencies.map(c => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  get reinsurerOptions(): SelectOption[] {
    return [
      { value: '', label: 'All reinsurers' },
      ...this.reinsurers.map(r => ({ value: r.id, label: r.name })),
    ];
  }

  get treatyOptions(): SelectOption[] {
    return [
      { value: '', label: 'All treaties' },
      ...this.treaties.map(t => ({
        value: t.id,
        label: `${t.treatyRef} - ${t.treatyType} (${t.status})`,
      })),
    ];
  }

  get quarterOptions(): SelectOption[] {
    return [1, 2, 3, 4].map(q => ({ value: String(q), label: `Q${q}` }));
  }

  get yearOptions(): SelectOption[] {
    const current = new Date().getUTCFullYear();
    return [current - 2, current - 1, current, current + 1]
      .map(y => ({ value: String(y), label: String(y) }));
  }

  fetch(): void {
    this.loading = true;
    this.svc.getRecoveriesBordereau(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
        this.surfaceWarnings(env);
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to load recoveries bordereau'));
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    this.exporting = true;
    this.svc.exportRecoveriesBordereauExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, this.filename());
        this.exporting = false;
        // Post-export: EXPECTED recoveries in this window were flipped
        // to INVOICED server-side (post-response). Reload to reflect it.
        this.fetch();
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to export recoveries bordereau'));
        this.exporting = false;
      },
    });
  }

  private surfaceWarnings(env: ReportResponse<RecoveriesBordereauRow[]>): void {
    const warnings = env?.warnings ?? [];
    if (warnings.length === 0) return;
    this.toast.warning(composeWarningsToast(warnings, 'Recoveries bordereau'), 8000);
  }

  onYearChange(value: string): void { this.year = Number(value); this.fetch(); }
  onQuarterChange(value: string): void { this.quarter = Number(value); this.fetch(); }
  onFilterChange(): void { this.fetch(); }

  perCurrencyList(): { currency: string; totalAmount: number; rowCount: number }[] {
    if (!this.envelope?.perCurrency) return [];
    return Object.entries(this.envelope.perCurrency)
      .map(([currency, v]) => ({ currency, totalAmount: v.totalAmount, rowCount: v.rowCount }))
      .sort((a, b) => a.currency.localeCompare(b.currency));
  }

  fxRateList(): { currency: string; rate: number }[] {
    if (!this.envelope?.fxRates) return [];
    return Object.entries(this.envelope.fxRates)
      .map(([currency, rate]) => ({ currency, rate }))
      .sort((a, b) => a.currency.localeCompare(b.currency));
  }

  get activeRows(): RecoveriesBordereauRow[] {
    return this.envelope?.data ?? [];
  }

  statusBadgeClass(status: RecoveriesBordereauRow['status']): string {
    switch (status) {
      case 'EXPECTED':    return 'badge badge-neutral';
      case 'INVOICED':    return 'badge badge-info';
      case 'RECEIVED':    return 'badge badge-success';
      case 'WRITTEN_OFF': return 'badge badge-danger';
    }
  }

  canAct(row: RecoveriesBordereauRow): boolean {
    return row.status === 'EXPECTED' || row.status === 'INVOICED';
  }

  openMarkReceived(row: RecoveriesBordereauRow): void {
    this.markReceivedTargetId = row.recoveryId;
    this.markReceivedTargetLabel = `${row.reinsurerName} - ${row.nativeExpected} ${row.currencyCode}`;
    this.markReceivedAmount = row.nativeExpected;
    this.markReceivedAt = new Date().toISOString().substring(0, 10);
  }

  cancelMarkReceived(): void {
    this.markReceivedTargetId = null;
    this.markReceivedTargetLabel = '';
    this.markReceivedAmount = null;
    this.markReceivedAt = '';
    this.markReceivedSubmitting = false;
  }

  submitMarkReceived(): void {
    if (!this.markReceivedTargetId) return;
    if (this.markReceivedAmount == null || this.markReceivedAmount < 0) {
      this.toast.warning('Received amount must be non-negative.');
      return;
    }
    const targetId = this.markReceivedTargetId;
    const payload: MarkRecoveryReceivedPayload = {
      receivedAmount: this.markReceivedAmount,
      receivedAt: this.markReceivedAt
        ? new Date(this.markReceivedAt + 'T00:00:00Z').toISOString()
        : undefined,
    };
    this.markReceivedSubmitting = true;
    this.actionInProgress[targetId] = true;
    this.svc.markRecoveryReceived(targetId, payload).subscribe({
      next: () => {
        this.actionInProgress[targetId] = false;
        this.cancelMarkReceived();
        this.fetch();
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to mark received'));
        this.markReceivedSubmitting = false;
        this.actionInProgress[targetId] = false;
      },
    });
  }

  openWriteOff(row: RecoveriesBordereauRow): void {
    this.writeOffTargetId = row.recoveryId;
    this.writeOffTargetLabel = `${row.reinsurerName} - ${row.nativeExpected} ${row.currencyCode}`;
    this.writeOffReason = '';
  }

  cancelWriteOff(): void {
    this.writeOffTargetId = null;
    this.writeOffTargetLabel = '';
    this.writeOffReason = '';
    this.writeOffSubmitting = false;
  }

  submitWriteOff(): void {
    if (!this.writeOffTargetId) return;
    if (!this.writeOffReason.trim()) {
      this.toast.warning('Reason is required.');
      return;
    }
    const targetId = this.writeOffTargetId;
    const payload: WriteOffRecoveryPayload = { reason: this.writeOffReason.trim() };
    this.writeOffSubmitting = true;
    this.actionInProgress[targetId] = true;
    this.svc.writeOffRecovery(targetId, payload).subscribe({
      next: () => {
        this.actionInProgress[targetId] = false;
        this.cancelWriteOff();
        this.fetch();
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to write off'));
        this.writeOffSubmitting = false;
        this.actionInProgress[targetId] = false;
      },
    });
  }

  private buildParams(): BordereauQuarterParams {
    return {
      reinsurerId:       this.reinsurerId || null,
      treatyId:          this.treatyId    || null,
      year:              this.year,
      quarter:           this.quarter,
      reportingCurrency: this.reportingCurrency || undefined,
    };
  }

  private filename(): string {
    const parts = ['recoveries-bordereau', `${this.year}-Q${this.quarter}`];
    if (this.reinsurerId) parts.push(`r${this.reinsurerId.substring(0, 8)}`);
    if (this.treatyId)    parts.push(`t${this.treatyId.substring(0, 8)}`);
    return `${parts.join('-')}.xlsx`;
  }
}

function currentQuarter(): number {
  return Math.floor(new Date().getUTCMonth() / 3) + 1;
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
