import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  BordereauQuarterParams,
  CessionBordereauRow,
  ReinsuranceService,
  Reinsurer,
  Treaty,
} from '../../../../../core/services/reinsurance.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';

/**
 * Cession bordereau — one row per (cession, participant) for the selected
 * quarter. Native amounts (R7); the perCurrency strip in the envelope
 * carries native subtotals and best-effort FX conversion to the reporting
 * currency. Prior-period adjustment badge fires when the cession row was
 * created after the first export of this quarter's bordereau.
 */
@Component({
  selector: 'app-cession-bordereau',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent],
  templateUrl: './cession-bordereau.component.html',
  styleUrl: '../receipts/receipts-report.component.scss',
})
export class CessionBordereauComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  envelope: ReportResponse<CessionBordereauRow[]> | null = null;

  currencies: TenantCurrencyConfig[] = [];
  reinsurers: Reinsurer[] = [];
  treaties: Treaty[] = [];

  year    = new Date().getUTCFullYear();
  quarter = currentQuarter();
  reinsurerId = '';
  treatyId    = '';
  reportingCurrency = '';

  constructor(
    private svc: ReinsuranceService,
    private currencyService: CurrencyService,
    private tenantService: TenantService,
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
      error: () => { /* non-fatal — picker just stays empty */ },
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
        label: `${t.treatyRef} — ${t.treatyType} (${t.status})`,
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
    this.errorMessage = null;
    this.svc.getCessionBordereau(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to load cession bordereau';
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    this.exporting = true;
    this.svc.exportCessionBordereauExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, this.filename());
        this.exporting = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title || 'Failed to export cession bordereau';
        this.exporting = false;
      },
    });
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

  get activeRows(): CessionBordereauRow[] {
    return this.envelope?.data ?? [];
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
    const parts = ['cession-bordereau', `${this.year}-Q${this.quarter}`];
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
