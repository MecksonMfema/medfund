import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PersistencyCohortParams,
  PersistencyCohortReportService,
  PersistencyCohortResult,
} from '../../../../../../core/services/persistency-cohort-report.service';
import { ReportResponse } from '../../../../../../core/services/report-envelope';
import { INSURANCE_LINES } from '../../../../../../core/models/insurance-lines';
import { IconComponent } from '../../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../../shared/components/select/select.component';

/**
 * Phase 13 §C Phase 10 — PERSISTENCY_COHORT report page. Configurable
 * checkpoint set via URL param (default 6/12/24 months). Freshness
 * banner surfaces when the HEALTH matview is >24h stale.
 */
@Component({
  selector: 'app-persistency-cohort-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent],
  templateUrl: './persistency-cohort.component.html',
  styleUrl: '../../receipts/receipts-report.component.scss',
})
export class PersistencyCohortReportComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  envelope: ReportResponse<PersistencyCohortResult> | null = null;

  periodStart = firstOfLast24Months();
  periodEnd   = lastOfPriorMonth();
  checkpoints = '6,12,24';
  insuranceLine = '';

  constructor(private reportSvc: PersistencyCohortReportService) {}

  ngOnInit(): void { this.fetch(); }

  get lineOptions(): SelectOption[] {
    return [
      { value: '', label: 'All lines' },
      ...INSURANCE_LINES.map(l => ({ value: l.value, label: l.label })),
    ];
  }

  fetch(): void {
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.loading = true;
    this.errorMessage = null;
    this.reportSvc.get(this.buildParams()).subscribe({
      next: env => { this.envelope = env; this.loading = false; },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to load persistency report';
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    this.exporting = true;
    this.reportSvc.exportExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `persistency-cohort-${this.periodStart}_${this.periodEnd}.xlsx`);
        this.exporting = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to export persistency report';
        this.exporting = false;
      },
    });
  }

  onFilterChange(): void { this.fetch(); }

  get rows() { return this.envelope?.data?.rows ?? []; }
  get freshnessWarning() { return this.envelope?.data?.freshnessWarning ?? null; }

  private buildParams(): PersistencyCohortParams {
    return {
      periodStart:   this.periodStart,
      periodEnd:     this.periodEnd,
      checkpoints:   this.checkpoints || undefined,
      insuranceLine: this.insuranceLine || null,
    };
  }
}

function firstOfLast24Months(): string {
  const now = new Date();
  const d = new Date(Date.UTC(now.getUTCFullYear() - 2, now.getUTCMonth(), 1));
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
