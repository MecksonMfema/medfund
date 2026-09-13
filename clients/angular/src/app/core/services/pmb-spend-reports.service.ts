import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Client for finance-service's PMB Spend async job orchestrator at
 * {@code /api/v1/reports/regulatory/pmb/spend/*}. Same submit → poll →
 * XLSX shape as the IFRS 17 client: poll status via
 * {@code ReportJobPollingService} and hit {@link #exportXlsxUrl} once
 * the job flips to {@code completed}.
 *
 * <p>Reporting currency is fixed at ZAR upstream so we do not send a
 * currency override; the server rejects a non-blank value with 422.
 */
export interface PmbSpendReportRequest {
  periodStart: string;
  periodEnd: string;
  /** Archive the composed XLSX to regulatory_submission (requires fresh
   *  MFA). Defaults to false: dry-run export for review. */
  submit?: boolean;
  attestationNote?: string | null;
}

export interface PmbSpendJobSubmissionResponse {
  jobId: string;
  status: string;
  /** True when an in-flight identical job was reused (same tenant +
   *  params hash). Frontend jumps straight into polling. */
  deduplicated: boolean;
}

@Injectable({ providedIn: 'root' })
export class PmbSpendReportsService {
  private readonly base = '/reports/regulatory/pmb/spend';

  constructor(private api: ApiService) {}

  submit(body: PmbSpendReportRequest): Observable<PmbSpendJobSubmissionResponse> {
    return this.api.post<PmbSpendJobSubmissionResponse>(`${this.base}/submit`, body);
  }

  exportXlsxUrl(jobId: string): string {
    return this.api.absoluteUrl(`${this.base}/jobs/${jobId}/xlsx`);
  }
}
