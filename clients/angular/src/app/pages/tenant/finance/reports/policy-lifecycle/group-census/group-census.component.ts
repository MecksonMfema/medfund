import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  GroupCensusParams,
  GroupCensusReportService,
  GroupCensusResult,
} from '../../../../../../core/services/group-census-report.service';
import { ReportResponse } from '../../../../../../core/services/report-envelope';
import { IconComponent } from '../../../../../../shared/components/icon/icon.component';
import { ReportBackButtonComponent } from '../../shared/report-back-button.component';
import { ToastService } from '../../../../../../shared/components/toast/toast.service';
import { composeWarningsToast, extractErrorMessage } from '../../../../../../core/util/http-errors';

/**
 * Phase 13 §C Phase 10: GROUP_CENSUS report page. Snapshot at
 * {@code asOf} with per-status member counts per group.
 */
@Component({
  selector: 'app-group-census-report',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, ReportBackButtonComponent],
  templateUrl: './group-census.component.html',
  styleUrls: [
    '../../receipts/receipts-report.component.scss',
    './group-census.component.scss',
  ],
})
export class GroupCensusReportComponent implements OnInit {
  loading = false;
  exporting = false;

  envelope: ReportResponse<GroupCensusResult> | null = null;
  asOf = today();

  constructor(
    private reportSvc: GroupCensusReportService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void { this.fetch(); }

  fetch(): void {
    if (!this.asOf) {
      this.toast.warning('asOf is required.');
      return;
    }
    this.loading = true;
    this.reportSvc.get(this.buildParams()).subscribe({
      next: env => {
        this.envelope = env;
        this.loading = false;
        this.surfaceWarnings(env);
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to load group census'));
        this.envelope = null;
        this.loading = false;
      },
    });
  }

  exportExcel(): void {
    if (!this.asOf) return;
    this.exporting = true;
    this.reportSvc.exportExcel(this.buildParams()).subscribe({
      next: blob => {
        downloadBlob(blob, `group-census-${this.asOf}.xlsx`);
        this.exporting = false;
      },
      error: err => {
        this.toast.error(extractErrorMessage(err, 'Failed to export group census'));
        this.exporting = false;
      },
    });
  }

  private surfaceWarnings(env: ReportResponse<GroupCensusResult>): void {
    const warnings = env?.warnings ?? [];
    if (warnings.length === 0) return;
    this.toast.warning(composeWarningsToast(warnings, 'Group census'), 8000);
  }

  onFilterChange(): void { this.fetch(); }

  get groups() { return this.envelope?.data?.groups ?? []; }

  private buildParams(): GroupCensusParams {
    return { asOf: this.asOf };
  }
}

function today(): string {
  const now = new Date();
  return now.toISOString().slice(0, 10);
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
