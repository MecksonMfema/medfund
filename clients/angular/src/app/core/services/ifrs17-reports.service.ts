import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Phase 15 §21 client for finance-service's
 * {@code /api/v1/reports/ifrs17/*} async job orchestrator.
 *
 * <p>Same submit/poll/export shape as
 * {@link ActuarialReportsService}: POST returns a jobId, the caller polls
 * via {@link ReportJobPollingService}, then hits {@link #exportXlsxUrl}
 * on completion for the workbook.
 */

/** Request body for {@code POST /reports/ifrs17/lrc-lic-reconciliation}
 *  and {@code POST /reports/ifrs17/insurance-revenue-service-result}. */
export interface Ifrs17ReportRequest {
  periodStart: string;
  periodEnd: string;
  /** Optional filter — null / empty means "every portfolio active in the
   *  requested period". Portfolio picker is deferred, so pages ship
   *  without a UI control and always send null. */
  portfolioIds?: string[] | null;
  /** Optional override; falls back to the tenant default when null. */
  reportingCurrency?: string | null;
}

/** Response body for both submits. */
export interface Ifrs17JobSubmissionResponse {
  jobId: string;
  status: string;
  chunkCount: number;
  /** True when an in-flight parent with the same params hash was reused
   *  rather than a fresh row inserted. */
  deduped: boolean;
}

/** Envelope shape written by finance-service's {@code Ifrs17JobAggregator}
 *  when every chunk terminates. Same JSON the XLSX writer consumes. */
export interface Ifrs17ResultEnvelope {
  summary: Ifrs17ResultSummary;
  portfolios: Record<string, { cohorts: Record<string, Record<string, Ifrs17ChunkLeaf>> }>;
}

export interface Ifrs17ResultSummary {
  totalChunks: number;
  completedChunks: number;
  failedChunks: number;
  measurementModelsSeen: string[];
  currenciesSeen: string[];
}

export interface Ifrs17ChunkLeaf {
  chunkId: string;
  status: string;
  model?: string;
  error?: string | null;
  resultRef?: string | null;
  result?: Record<string, unknown> | null;
}

@Injectable({ providedIn: 'root' })
export class Ifrs17ReportsService {
  constructor(private api: ApiService) {}

  submitLrcLicReconciliation(body: Ifrs17ReportRequest): Observable<Ifrs17JobSubmissionResponse> {
    return this.api.post<Ifrs17JobSubmissionResponse>(
      '/reports/ifrs17/lrc-lic-reconciliation', body);
  }

  submitInsuranceRevenueServiceResult(body: Ifrs17ReportRequest): Observable<Ifrs17JobSubmissionResponse> {
    return this.api.post<Ifrs17JobSubmissionResponse>(
      '/reports/ifrs17/insurance-revenue-service-result', body);
  }

  /** Direct URL for the browser to download — matches
   *  {@code ActuarialReportsService.exportXlsxUrl}. */
  exportXlsxUrl(jobId: string): string {
    return this.api.absoluteUrl(`/reports/ifrs17/jobs/${jobId}/export.xlsx`);
  }
}
