import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ClawbackRegisterParams,
  ClawbackRegisterRow,
  ClawbackSource,
  CommissionReportService,
} from '../../../../../core/services/commission-report.service';
import { Producer, ProducerService } from '../../../../../core/services/producer.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import { ReportBackButtonComponent } from '../shared/report-back-button.component';

/**
 * Commission clawback register — one row per clawback_event in the selected
 * period. Folds both trigger sources (MEMBER_LAPSE + CONTRIBUTION_REVOKE)
 * into one flat view; optional source filter narrows to a single trigger.
 * Native amounts; the per-currency strip carries native subtotals + FX to
 * the reporting currency.
 */
@Component({
  selector: 'app-commission-clawback-register',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, ReportBackButtonComponent],
  templateUrl: './commission-clawback-register.component.html',
  styleUrl: '../receipts/receipts-report.component.scss',
})
export class CommissionClawbackRegisterComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  envelope: ReportResponse<ClawbackRegisterRow[]> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  periodStart = firstOfPriorMonth();
  periodEnd   = lastOfPriorMonth();
  reportingCurrency = '';
  source: ClawbackSource | '' = '';

  producerId: string | null = null;
  producerLabel: string | null = null;
  producerSearchQuery = '';
  producerMatches: Producer[] = [];
  producerSearching = false;
  private producerSearchTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private reportSvc: CommissionReportService,
    private producerSvc: ProducerService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    this.loadCurrencies();
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

  get currencyOptions(): SelectOption[] {
    return [
      { value: '', label: 'Tenant default' },
      ...this.currencies.map(c => ({
        value: c.currencyCode,
        label: `${c.currencyCode}${c.isDefault ? ' (default)' : ''}`,
      })),
    ];
  }

  get sourceOptions(): SelectOption[] {
    return [
      { value: '',                     label: 'All sources' },
      { value: 'MEMBER_LAPSE',         label: 'Member lapse' },
      { value: 'CONTRIBUTION_REVOKE',  label: 'Contribution revoke' },
    ];
  }

  fetch(): void {
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.reportSvc.getClawbackRegister(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to load clawback register';
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.exporting = true;
    this.reportSvc.exportClawbackRegisterExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, this.filename());
        this.exporting = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to export clawback register';
        this.exporting = false;
      },
    });
  }

  onFilterChange(): void { this.fetch(); }

  onSourceChange(value: string): void {
    this.source = (value as ClawbackSource | '') || '';
    this.fetch();
  }

  onProducerSearchChange(): void {
    if (this.producerSearchTimer) clearTimeout(this.producerSearchTimer);
    const q = this.producerSearchQuery.trim();
    if (!q) { this.producerMatches = []; return; }
    this.producerSearching = true;
    this.producerSearchTimer = setTimeout(() => {
      this.producerSvc.searchProducers(q, 10).subscribe({
        next: rows => { this.producerMatches = rows; this.producerSearching = false; },
        error: () => { this.producerMatches = []; this.producerSearching = false; },
      });
    }, 300);
  }

  pickProducer(p: Producer): void {
    this.producerId    = p.id;
    this.producerLabel = `${p.producerCode} - ${p.name}`;
    this.producerSearchQuery = '';
    this.producerMatches = [];
    this.fetch();
  }

  clearProducer(): void {
    this.producerId    = null;
    this.producerLabel = null;
    this.fetch();
  }

  sourceBadgeClass(source: ClawbackSource): string {
    return source === 'MEMBER_LAPSE' ? 'badge badge-warning' : 'badge badge-info';
  }

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

  get activeRows(): ClawbackRegisterRow[] {
    return this.envelope?.data ?? [];
  }

  private buildParams(): ClawbackRegisterParams {
    return {
      periodStart:       this.periodStart,
      periodEnd:         this.periodEnd,
      producerId:        this.producerId,
      source:            this.source || undefined,
      reportingCurrency: this.reportingCurrency || undefined,
    };
  }

  private filename(): string {
    const parts = ['commission-clawback', `${this.periodStart}_${this.periodEnd}`];
    if (this.producerId) parts.push(`p${this.producerId.substring(0, 8)}`);
    if (this.source)     parts.push(this.source.toLowerCase());
    return `${parts.join('-')}.xlsx`;
  }
}

function firstOfPriorMonth(): string {
  const now = new Date();
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1));
  return d.toISOString().slice(0, 10);
}
function lastOfPriorMonth(): string {
  const now = new Date();
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 0));
  return d.toISOString().slice(0, 10);
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
