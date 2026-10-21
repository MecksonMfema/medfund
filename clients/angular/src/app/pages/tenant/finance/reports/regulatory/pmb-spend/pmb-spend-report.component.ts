import { Component, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import {
  JobStatusResponse,
  ReportJobPollingService,
} from '../../../../../../core/services/report-job-polling.service';
import {
  PmbSpendReportRequest,
  PmbSpendReportsService,
} from '../../../../../../core/services/pmb-spend-reports.service';
import { IconComponent } from '../../../../../../shared/components/icon/icon.component';
import {
  ActuarialJobProgressComponent,
} from '../../../../../../shared/components/actuarial-job-progress/actuarial-job-progress.component';
import { ReportBackButtonComponent } from '../../shared/report-back-button.component';
import { ToastService } from '../../../../../../shared/components/toast/toast.service';
import { extractErrorMessage } from '../../../../../../core/util/http-errors';

/**
 * CMS Prescribed Minimum Benefit (PMB) spend report page. Same submit →
 * poll → XLSX shape as the IFRS 17 pages, but there is no in-page rich
 * render: the whole point of the report is the CMS-templated workbook.
 * The page therefore only carries the period picker, run button, job
 * progress card, and a big "download XLSX" CTA once the job flips to
 * completed.
 *
 * <p>Reporting currency is fixed at ZAR upstream; we do not offer a
 * currency picker because the server rejects any override with 422.
 */
@Component({
  selector: 'app-pmb-spend-report',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IconComponent,
    ActuarialJobProgressComponent,
    ReportBackButtonComponent,
  ],
  templateUrl: './pmb-spend-report.component.html',
  styleUrls: [
    '../../receipts/receipts-report.component.scss',
    './pmb-spend-report.component.scss',
  ],
})
export class PmbSpendReportComponent implements OnDestroy {
  readonly reportKey = 'PMB_SPEND';
  readonly pageTitle = 'PMB spend report';
  readonly pageSubtitle =
    'CMS Prescribed Minimum Benefit annual spend. Reporting currency is fixed at ZAR. ' +
    'The workbook is produced asynchronously; download when the job completes.';

  periodStart = firstOfPreviousYear();
  periodEnd = lastOfPreviousYear();

  submitting = false;
  currentJob: JobStatusResponse | null = null;
  startedAt: number | null = null;
  private pollSub: Subscription | null = null;

  constructor(
    private reports: PmbSpendReportsService,
    private polling: ReportJobPollingService,
    private toast: ToastService,
  ) {}

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  submit(): void {
    if (this.submitting) return;
    if (!this.periodStart || !this.periodEnd) {
      this.toast.warning('Choose a start and end date.');
      return;
    }
    this.submitting = true;
    this.currentJob = null;
    this.startedAt = Date.now();

    const body: PmbSpendReportRequest = {
      periodStart: this.periodStart,
      periodEnd: this.periodEnd,
    };

    this.reports.submit(body).subscribe({
      next: (resp) => {
        this.submitting = false;
        this.beginPolling(resp.jobId);
      },
      error: (err) => {
        this.submitting = false;
        this.toast.error(extractErrorMessage(err, 'Failed to submit the PMB spend report'));
      },
    });
  }

  cancelPolling(): void {
    this.pollSub?.unsubscribe();
    this.pollSub = null;
  }

  exportXlsx(): void {
    if (!this.currentJob || this.currentJob.status !== 'completed') return;
    window.open(this.reports.exportXlsxUrl(this.currentJob.jobId), '_blank');
  }

  private beginPolling(jobId: string): void {
    this.pollSub?.unsubscribe();
    this.pollSub = this.polling.poll(jobId).subscribe({
      next: (snap) => { this.currentJob = snap; },
      error: (err) => {
        this.toast.error(err?.message || 'Polling failed');
        this.pollSub = null;
      },
      complete: () => { this.pollSub = null; },
    });
  }
}

function firstOfPreviousYear(): string {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear() - 1, 0, 1)).toISOString().slice(0, 10);
}

function lastOfPreviousYear(): string {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear() - 1, 11, 31)).toISOString().slice(0, 10);
}
