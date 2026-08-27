import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';

/**
 * Phase 14 §Actuarial Phase 10 client for finance-service's
 * {@code /api/v1/reports/actuarial/*} async job orchestrator.
 *
 * <p>The compute pipeline is Kafka-mediated (finance-service publishes,
 * ai-service consumes, finance-service consumes results) so every submit
 * returns a jobId immediately; the caller then polls
 * {@link #status} until the row lands in a terminal state
 * (completed | failed). {@link ActuarialJobPollingService} wraps that
 * polling loop for the report pages.
 */

/** Request body — shared by both IBNR and LOSS_TRIANGLE submit endpoints. */
export interface TriangleJobRequest {
  periodStart: string;
  periodEnd: string;
  insuranceLine?: string | null;
  shape: 'paid' | 'incurred' | 'reported';
  grain: 'month' | 'quarter' | 'year';
  reportingCurrency?: string | null;
  ldfMethod?: string | null;
}

/** Request body for {@code POST /reports/actuarial/persistency-study}. */
export interface PersistencyStudyJobRequest {
  periodStart: string;
  periodEnd: string;
  checkpoints?: number[] | null;
  insuranceLine?: string | null;
  reportingCurrency?: string | null;
}

/** Row shape published by the ai-service persistency compute. */
export interface PersistencyResultRow {
  cohort_month: string;
  checkpoint_months: number;
  cohort_size: number;
  retained_count: number;
  actual_retention_pct: number;
  expected_retention_pct: number | null;
  ae_ratio: number | null;
}

export interface PersistencyResult {
  per_line: Record<string, PersistencyResultRow[]>;
  warnings: string[];
}

/** Cohort input carried on {@code paramsJson.cohort} for the persistency job. */
export interface PersistencyCohortPayload {
  cohorts: Array<{
    cohort_month: string;
    insurance_line: string;
    cohort_size: number;
    checkpoints: Array<{ months: number; retained_count: number }>;
  }>;
  expected_basis: Record<
    string,
    Array<{ cohort_months: number; expected_retention_pct: number }>
  >;
}

/** Inline response returned by every submit. */
export interface JobSubmissionResponse {
  jobId: string;
  status: string;
  deduplicated: boolean;
}

/** Chainladder result payload — snake_case straight from the Python compute. */
export interface ChainLadderResult {
  ldfs: number[];
  cdf: number[];
  ibnr_total: number;
  ultimate_total: number;
  mack_standard_error: number | null;
  per_cohort_ultimate: number[];
}

/**
 * Poll response envelope. {@code resultJson} is the raw Python payload —
 * for IBNR/LOSS it deserialises to {@link ChainLadderResult}. Later
 * phases (persistency, mortality, morbidity, lapse) reuse this shape but
 * with their own compute payload; the reports resolve the appropriate
 * shape when rendering.
 */
/**
 * Shape stashed into {@code params_json} after
 * finance-service {@code TriangleShapingService} runs. Both the XLSX
 * export and the Angular split-view read this same shape.
 */
export interface TrianglePayload {
  accident_periods: string[];
  development_periods: string[];
  cells: (number | null)[][];
  grain: 'month' | 'quarter' | 'year';
  reporting_currency: string;
  insurance_line: string;
}

export interface JobParams {
  periodStart: string;
  periodEnd: string;
  insuranceLine: string;
  shape?: string;
  grain?: string;
  reportingCurrency: string;
  ldfMethod?: string;
  checkpoints?: number[];
  triangle?: TrianglePayload;
  cohort?: PersistencyCohortPayload;
  shape_warnings?: string[];
}

export interface JobStatusResponse {
  jobId: string;
  reportKey: string;
  status: 'requested' | 'processing' | 'completed' | 'failed';
  progressPct: number;
  paramsJson: JobParams | null;
  resultJson: ChainLadderResult | Record<string, unknown> | null;
  errorMessage: string | null;
  requestedAt: string;
  completedAt: string | null;
}

@Injectable({ providedIn: 'root' })
export class ActuarialReportsService {
  constructor(private api: ApiService) {}

  submitIbnr(body: TriangleJobRequest): Observable<JobSubmissionResponse> {
    return this.api.post<JobSubmissionResponse>('/reports/actuarial/ibnr', body);
  }

  submitLoss(body: TriangleJobRequest): Observable<JobSubmissionResponse> {
    return this.api.post<JobSubmissionResponse>('/reports/actuarial/loss-triangle', body);
  }

  submitPersistencyStudy(body: PersistencyStudyJobRequest): Observable<JobSubmissionResponse> {
    return this.api.post<JobSubmissionResponse>('/reports/actuarial/persistency-study', body);
  }

  status(jobId: string): Observable<JobStatusResponse> {
    return this.api.get<JobStatusResponse>(`/reports/actuarial/jobs/${jobId}`);
  }

  /**
   * Fully-qualified URL for the XLSX export — used as a direct
   * {@code <a href>} so the browser handles the download rather than
   * routing bytes through the Angular HttpClient.
   */
  exportXlsxUrl(jobId: string): string {
    return this.api.absoluteUrl(`/reports/actuarial/jobs/${jobId}/export.xlsx`);
  }

  /** {@link #exportXlsxUrl} as a Blob for programmatic download (spec parity). */
  exportXlsxBlob(jobId: string): Observable<Blob> {
    return this.api.getBlob(`/reports/actuarial/jobs/${jobId}/export.xlsx`);
  }
}
