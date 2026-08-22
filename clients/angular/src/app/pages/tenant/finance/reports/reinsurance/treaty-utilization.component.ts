import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ReinsuranceService,
  Treaty,
  TreatyUtilizationParams,
  TreatyUtilizationRow,
} from '../../../../../core/services/reinsurance.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';

/**
 * Treaty utilization — since-inception aggregate by (treaty, layer,
 * ceded currency). XoL/StopLoss surface per-layer rows; QS/SS collapse
 * to a single logical layer (layerId=null). Utilization percentage is
 * client-side since either denominator can be null (aggregateLimit,
 * layerLimit).
 */
@Component({
  selector: 'app-treaty-utilization',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent],
  templateUrl: './treaty-utilization.component.html',
  styleUrl: '../receipts/receipts-report.component.scss',
})
export class TreatyUtilizationComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  envelope: ReportResponse<TreatyUtilizationRow[]> | null = null;

  currencies: TenantCurrencyConfig[] = [];
  treaties: Treaty[] = [];

  treatyId = '';
  asOfDate = todayISO();
  reportingCurrency = '';

  constructor(
    private svc: ReinsuranceService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
  ) {}

  ngOnInit(): void {
    this.loadCurrencies();
    this.loadTreaties();
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

  private loadTreaties(): void {
    this.svc.listTreaties(0, 200).subscribe({
      next: page => {
        this.treaties = page.content;
        // Auto-select first active treaty so the report populates on land.
        const active = this.treaties.find(t => t.status === 'ACTIVE');
        if (active && !this.treatyId) {
          this.treatyId = active.id;
          this.fetch();
        }
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

  get treatyOptions(): SelectOption[] {
    return this.treaties.map(t => ({
      value: t.id,
      label: `${t.treatyRef} — ${t.treatyType} (${t.status})`,
    }));
  }

  fetch(): void {
    if (!this.treatyId) {
      this.envelope = null;
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.svc.getTreatyUtilization(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load treaty utilization';
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.treatyId) return;
    this.exporting = true;
    this.svc.exportTreatyUtilizationExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, this.filename());
        this.exporting = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to export treaty utilization';
        this.exporting = false;
      },
    });
  }

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

  get activeRows(): TreatyUtilizationRow[] {
    return this.envelope?.data ?? [];
  }

  /** Per-layer utilization if layerLimit set; else per-treaty utilization
   *  against aggregateLimit; else null (n/a). */
  utilizationPct(row: TreatyUtilizationRow): number | null {
    const denom = row.layerLimit ?? row.aggregateLimit;
    if (denom == null || denom === 0) return null;
    // Only ratio ceded-in-native / limit-in-native when currencies match;
    // otherwise leave n/a to avoid a cross-currency compare (R7 / CLAUDE.md).
    const denomCurrency = row.layerLimit != null ? row.layerCurrency : row.aggregateLimitCurrency;
    if (!denomCurrency || denomCurrency !== row.cededCurrency) return null;
    return (row.totalCededNative / denom) * 100;
  }

  private buildParams(): TreatyUtilizationParams {
    return {
      treatyId:          this.treatyId,
      asOfDate:          this.asOfDate || undefined,
      reportingCurrency: this.reportingCurrency || undefined,
    };
  }

  private filename(): string {
    const short = this.treatyId ? this.treatyId.substring(0, 8) : 'treaty';
    return `treaty-utilization-${short}-${this.asOfDate || todayISO()}.xlsx`;
  }
}

function todayISO(): string {
  const d = new Date();
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()))
    .toISOString().slice(0, 10);
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
