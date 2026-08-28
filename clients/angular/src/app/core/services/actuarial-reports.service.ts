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

/** Request body for {@code POST /reports/actuarial/lapse-study}. */
export interface LapseStudyJobRequest {
  periodStart: string;
  periodEnd: string;
  checkpoints?: number[] | null;
  insuranceLine?: string | null;
  reportingCurrency?: string | null;
}

/** Request body for {@code POST /reports/actuarial/mortality-study}. */
export interface MortalityStudyJobRequest {
  periodStart: string;
  periodEnd: string;
  insuranceLine?: string | null;
  basisNameOverride?: string | null;
  multiplierOverride?: number | null;
  reportingCurrency?: string | null;
}

/** Request body for {@code POST /reports/actuarial/morbidity-study}. */
export interface MorbidityStudyJobRequest {
  periodStart: string;
  periodEnd: string;
  insuranceLine?: string | null;
  basisNameOverride?: string | null;
  multiplierOverride?: number | null;
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

/** Row shape published by the ai-service lapse compute. */
export interface LapseResultRow {
  cohort_month: string;
  checkpoint_months: number;
  cohort_size: number;
  lapsed_count: number;
  actual_lapse_pct: number;
  expected_lapse_pct: number | null;
  ae_ratio: number | null;
}

export interface LapseResult {
  per_line: Record<string, LapseResultRow[]>;
  warnings: string[];
}

/** Row shape published by the ai-service mortality compute. */
export interface MortalityResultRow {
  age_band: string;
  sex: string;
  exposure_years: number;
  observed_deaths: number;
  expected_deaths: number;
  actual_mortality_rate: number;
  expected_mortality_rate: number;
  ae_ratio: number | null;
}

/** Per-line envelope from the ai-service mortality compute. */
export interface MortalityLineResult {
  basis_name: string;
  multiplier: number;
  rows: MortalityResultRow[];
}

export interface MortalityResult {
  per_line: Record<string, MortalityLineResult>;
  warnings: string[];
}

/** Exposure input carried on {@code paramsJson.exposure} for the mortality job. */
export interface MortalityExposurePayload {
  cohorts: Array<{
    insurance_line: string;
    basis_name: string;
    multiplier: number;
    bands: Array<{ age_band: string; sex: string; exposure_years: number; deaths: number }>;
  }>;
}

/** Row shape published by the ai-service morbidity compute. */
export interface MorbidityResultRow {
  age_band: string;
  sex: string;
  exposure_years: number;
  observed_incidents: number;
  expected_incidents: number;
  actual_morbidity_rate: number;
  expected_morbidity_rate: number;
  ae_ratio: number | null;
}

/** Per-line envelope from the ai-service morbidity compute. */
export interface MorbidityLineResult {
  basis_name: string;
  multiplier: number;
  rows: MorbidityResultRow[];
}

export interface MorbidityResult {
  per_line: Record<string, MorbidityLineResult>;
  warnings: string[];
}

/** Exposure input carried on {@code paramsJson.exposure} for the morbidity job. */
export interface MorbidityExposurePayload {
  cohorts: Array<{
    insurance_line: string;
    basis_name: string;
    multiplier: number;
    bands: Array<{ age_band: string; sex: string; exposure_years: number; incidents: number }>;
  }>;
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

/** Cohort input carried on {@code paramsJson.cohort} for the lapse job. */
export interface LapseCohortPayload {
  cohorts: Array<{
    cohort_month: string;
    insurance_line: string;
    cohort_size: number;
    checkpoints: Array<{ months: number; still_active: number }>;
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
  cohort?: PersistencyCohortPayload | LapseCohortPayload;
  exposure?: MortalityExposurePayload | MorbidityExposurePayload;
  basisNameOverride?: string;
  multiplierOverride?: string;
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

  submitLapseStudy(body: LapseStudyJobRequest): Observable<JobSubmissionResponse> {
    return this.api.post<JobSubmissionResponse>('/reports/actuarial/lapse-study', body);
  }

  submitMortalityStudy(body: MortalityStudyJobRequest): Observable<JobSubmissionResponse> {
    return this.api.post<JobSubmissionResponse>('/reports/actuarial/mortality-study', body);
  }

  submitMorbidityStudy(body: MorbidityStudyJobRequest): Observable<JobSubmissionResponse> {
    return this.api.post<JobSubmissionResponse>('/reports/actuarial/morbidity-study', body);
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
