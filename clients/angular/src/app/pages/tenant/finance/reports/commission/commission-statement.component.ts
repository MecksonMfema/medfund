import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  CommissionReportService,
  CommissionStatementParams,
  CommissionStatementRow,
} from '../../../../../core/services/commission-report.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { CurrencyService, TenantCurrencyConfig } from '../../../../../core/services/currency.service';
import { TenantService } from '../../../../../core/services/tenant.service';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';
import {
  EntityPickerComponent,
  EntityPickerSelection,
} from '../../../../../shared/components/entity-picker/entity-picker.component';
import { ReportBackButtonComponent } from '../shared/report-back-button.component';

/**
 * Commission statement — one row per commission_transaction in the selected
 * period. Native amounts (per parent-plan invariant #1); the per-currency
 * strip carries native subtotals with best-effort FX conversion to the
 * reporting currency. Optional producer filter narrows the ledger to a
 * single broker via a debounced search-select
 * (per {@code feedback_no_raw_id_inputs}).
 */
@Component({
  selector: 'app-commission-statement',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, EntityPickerComponent, ReportBackButtonComponent],
  templateUrl: './commission-statement.component.html',
  styleUrl: '../receipts/receipts-report.component.scss',
})
export class CommissionStatementComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  envelope: ReportResponse<CommissionStatementRow[]> | null = null;
  currencies: TenantCurrencyConfig[] = [];

  periodStart = firstOfPriorMonth();
  periodEnd   = lastOfPriorMonth();
  reportingCurrency = '';

  producerId: string | null = null;
  producerLabel: string | null = null;

  constructor(
    private reportSvc: CommissionReportService,
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

  fetch(): void {
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.reportSvc.getStatement(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to load commission statement';
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.periodStart || !this.periodEnd) return;
    this.exporting = true;
    this.reportSvc.exportStatementExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, this.filename());
        this.exporting = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to export commission statement';
        this.exporting = false;
      },
    });
  }

  onFilterChange(): void { this.fetch(); }

  onProducerPicked(sel: EntityPickerSelection | null): void {
    if (sel) {
      this.producerId = sel.id;
      this.producerLabel = sel.label;
    } else {
      this.producerId = null;
      this.producerLabel = null;
    }
    this.fetch();
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

  get activeRows(): CommissionStatementRow[] {
    return this.envelope?.data ?? [];
  }

  private buildParams(): CommissionStatementParams {
    return {
      periodStart:       this.periodStart,
      periodEnd:         this.periodEnd,
      producerId:        this.producerId,
      reportingCurrency: this.reportingCurrency || undefined,
    };
  }

  private filename(): string {
    const parts = ['commission-statement', `${this.periodStart}_${this.periodEnd}`];
    if (this.producerId) parts.push(`p${this.producerId.substring(0, 8)}`);
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
