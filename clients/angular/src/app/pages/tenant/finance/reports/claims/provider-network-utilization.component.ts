import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ProviderNetworkUtilizationReportService,
  ProviderUtilizationParams,
  ProviderUtilizationResult,
} from '../../../../../core/services/provider-network-utilization-report.service';
import { ReportResponse } from '../../../../../core/services/report-envelope';
import { INSURANCE_LINES } from '../../../../../core/models/insurance-lines';
import { IconComponent } from '../../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../../shared/components/select/select.component';

/**
 * Phase 13 §C Phase 10 — PROVIDER_NETWORK_UTILIZATION report page.
 * Two-level layout: per-tier summary table + per-provider detail table.
 * Envelope warnings surface peer-down (user-service unreachable) and
 * missing FX rates.
 */
@Component({
  selector: 'app-provider-network-utilization-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent],
  templateUrl: './provider-network-utilization.component.html',
  styleUrl: '../receipts/receipts-report.component.scss',
})
export class ProviderNetworkUtilizationReportComponent implements OnInit {
  loading = false;
  exporting = false;
  errorMessage: string | null = null;

  envelope: ReportResponse<ProviderUtilizationResult> | null = null;

  periodStart = firstOfPriorMonth();
  periodEnd   = lastOfPriorMonth();
  insuranceLine = '';
  networkTier   = '';

  constructor(private reportSvc: ProviderNetworkUtilizationReportService) {}

  ngOnInit(): void { this.fetch(); }

  get lineOptions(): SelectOption[] {
    return [
      { value: '', label: 'All lines' },
      ...INSURANCE_LINES.map(l => ({ value: l.value, label: l.label })),
    ];
  }
  get tierOptions(): SelectOption[] {
    return [
      { value: '',         label: 'All tiers' },
      { value: 'STANDARD', label: 'Standard' },
      { value: 'TIER_1',   label: 'Tier 1' },
      { value: 'TIER_2',   label: 'Tier 2' },
      { value: 'TIER_3',   label: 'Tier 3' },
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
          || 'Failed to load provider utilization report';
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    this.exporting = true;
    this.reportSvc.exportExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `provider-network-utilization-${this.periodStart}_${this.periodEnd}.xlsx`);
        this.exporting = false;
      },
      error: err => {
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to export provider utilization report';
        this.exporting = false;
      },
    });
  }

  onFilterChange(): void { this.fetch(); }

  get detail() { return this.envelope?.data?.detail ?? []; }
  get warnings() { return this.envelope?.warnings ?? []; }
  summaryEntries(): { tier: string; providerCount: number; claimCount: number;
                       totalPaid: string; denialCount: number; uniqueMembers: number }[] {
    const s = this.envelope?.data?.summary ?? {};
    return Object.entries(s).map(([tier, v]) => ({
      tier,
      providerCount: v.providerCount,
      claimCount:    v.claimCount,
      totalPaid:     v.totalPaid,
      denialCount:   v.denialCount,
      uniqueMembers: v.uniqueMembers,
    }));
  }

  private buildParams(): ProviderUtilizationParams {
    return {
      periodStart:   this.periodStart,
      periodEnd:     this.periodEnd,
      insuranceLine: this.insuranceLine || null,
      networkTier:   this.networkTier   || null,
    };
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
