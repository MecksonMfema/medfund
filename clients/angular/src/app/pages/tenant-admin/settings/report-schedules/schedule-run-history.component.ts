import { Component, Input, OnInit } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import {
  ScheduleRunRow,
  TenantReportScheduleService,
} from '../../../../core/services/tenant-report-schedule.service';

/**
 * Table of the newest {@code limit} scheduled fires for a schedule. Fetched
 * lazily on {@code load()} so the accordion doesn't hit the network until the
 * admin opens the "Run history" details panel. Supports download of any
 * completed run and a rerun action.
 */
@Component({
  selector: 'app-schedule-run-history',
  standalone: true,
  imports: [CommonModule, IconComponent, SkeletonComponent, DatePipe],
  templateUrl: './schedule-run-history.component.html',
  styleUrl: './schedule-run-history.component.scss',
})
export class ScheduleRunHistoryComponent implements OnInit {
  @Input({ required: true }) scheduleId!: string;
  @Input() limit = 20;

  runs: ScheduleRunRow[] = [];
  loading = false;
  errorMessage: string | null = null;
  rerunning: Record<string, boolean> = {};

  constructor(
    private service: TenantReportScheduleService,
    private toast: ToastService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.errorMessage = null;
    this.service.runHistory(this.scheduleId, this.limit).subscribe({
      next: (rows) => {
        this.runs = rows;
        this.loading = false;
      },
      error: (err) => {
        this.errorMessage = err?.error?.detail || 'Could not load run history.';
        this.loading = false;
      },
    });
  }

  download(row: ScheduleRunRow): void {
    if (!row.hasXlsx) return;
    this.service.downloadRun(row.jobId).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = this.filenameFor(row);
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(() => URL.revokeObjectURL(url), 0);
      },
      error: (err) => this.toast.error(err?.error?.detail || 'Download failed'),
    });
  }

  rerun(row: ScheduleRunRow): void {
    this.rerunning[row.jobId] = true;
    this.service.rerun(row.jobId).subscribe({
      next: () => {
        this.rerunning[row.jobId] = false;
        this.toast.success('Rerun queued: refreshing history…');
        // The rerun is asynchronous; give the orchestrator a couple of seconds
        // to insert the fresh report_job row before we re-fetch.
        setTimeout(() => this.load(), 2000);
      },
      error: (err) => {
        this.rerunning[row.jobId] = false;
        this.toast.error(err?.error?.detail || 'Rerun failed');
      },
    });
  }

  durationSeconds(row: ScheduleRunRow): number | null {
    if (!row.completedAt || !row.requestedAt) return null;
    const start = Date.parse(row.requestedAt);
    const end = Date.parse(row.completedAt);
    if (Number.isNaN(start) || Number.isNaN(end)) return null;
    return (end - start) / 1000;
  }

  statusPillClass(status: string): string {
    switch ((status || '').toLowerCase()) {
      case 'completed': return 'pill-success';
      case 'failed':    return 'pill-danger';
      case 'processing':
      case 'requested': return 'pill-info';
      default:          return 'pill-muted';
    }
  }

  private filenameFor(row: ScheduleRunRow): string {
    const base = (row.reportKey || 'report').toLowerCase();
    if (row.periodStart && row.periodEnd && row.periodStart !== row.periodEnd) {
      return `${base}_${row.periodStart}_${row.periodEnd}.xlsx`;
    }
    if (row.periodStart) return `${base}_${row.periodStart}.xlsx`;
    return `${base}.xlsx`;
  }
}
