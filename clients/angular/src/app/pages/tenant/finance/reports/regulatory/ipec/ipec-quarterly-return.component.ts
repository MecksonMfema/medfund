import { Component, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import {
  JobStatusResponse,
  ReportJobPollingService,
} from '../../../../../../core/services/report-job-polling.service';
import {
  IpecReportRequest,
  IpecReportsService,
} from '../../../../../../core/services/ipec-reports.service';
import { IconComponent } from '../../../../../../shared/components/icon/icon.component';
import {
  ActuarialJobProgressComponent,
} from '../../../../../../shared/components/actuarial-job-progress/actuarial-job-progress.component';
import { ReportBackButtonComponent } from '../../shared/report-back-button.component';

/**
 * IPEC (Zimbabwe) quarterly return page. Async submit → poll → XLSX,
 * mirroring the PMB spend / tax-return pages. There is no in-page rich
 * render because the deliverable is the IPEC-templated workbook; the
 * page carries a period picker, run button, job progress card, and a
 * Download XLSX CTA that lights up once the job is completed.
 *
 * <p>Reporting currency is fixed at ZWL upstream; no picker is offered
 * because the server rejects a non-blank override with 422.
 */
@Component({
  selector: 'app-ipec-quarterly-return',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IconComponent,
    ActuarialJobProgressComponent,
    ReportBackButtonComponent,
  ],
  templateUrl: './ipec-quarterly-return.component.html',
  styleUrls: [
    '../../receipts/receipts-report.component.scss',
  ],
})
export class IpecQuarterlyReturnComponent implements OnDestroy {
  readonly reportKey = 'IPEC_QUARTERLY_RETURN';
  readonly pageTitle = 'IPEC quarterly return';
  readonly pageSubtitle =
    'Zimbabwe IPEC short-term insurance quarterly return. Reporting currency is fixed at ZWL. ' +
    'The workbook is produced asynchronously; download when the job completes.';

  periodStart = firstOfPreviousQuarter();
  periodEnd = lastOfPreviousQuarter();

  submitting = false;
  errorMessage: string | null = null;
  currentJob: JobStatusResponse | null = null;
  startedAt: number | null = null;
  private pollSub: Subscription | null = null;

  constructor(
    private reports: IpecReportsService,
    private polling: ReportJobPollingService,
  ) {}

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  submit(): void {
    if (this.submitting) return;
    if (!this.periodStart || !this.periodEnd) {
      this.errorMessage = 'Choose a start and end date.';
      return;
    }
    this.errorMessage = null;
    this.submitting = true;
    this.currentJob = null;
    this.startedAt = Date.now();

    const body: IpecReportRequest = {
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
        this.errorMessage = err?.error?.detail || err?.error?.title
          || 'Failed to submit the IPEC quarterly return';
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
        this.errorMessage = err?.message || 'Polling failed';
        this.pollSub = null;
      },
      complete: () => { this.pollSub = null; },
    });
  }
}

function firstOfPreviousQuarter(): string {
  const now = new Date();
  const currentQuarter = Math.floor(now.getUTCMonth() / 3);
  const prevQuarter = currentQuarter - 1;
  const year = prevQuarter < 0 ? now.getUTCFullYear() - 1 : now.getUTCFullYear();
  const monthIndex = ((prevQuarter + 4) % 4) * 3;
  return new Date(Date.UTC(year, monthIndex, 1)).toISOString().slice(0, 10);
}

function lastOfPreviousQuarter(): string {
  const now = new Date();
  const currentQuarter = Math.floor(now.getUTCMonth() / 3);
  const prevQuarter = currentQuarter - 1;
  const year = prevQuarter < 0 ? now.getUTCFullYear() - 1 : now.getUTCFullYear();
  const monthIndex = ((prevQuarter + 4) % 4) * 3 + 2;
  const lastDay = new Date(Date.UTC(year, monthIndex + 1, 0));
  return lastDay.toISOString().slice(0, 10);
}
