import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { ReportResponse } from './report-envelope';

/**
 * Phase 13 §C Phase 10 — PERSISTENCY_COHORT report data layer. HEALTH
 * cohort reads {@code member_first_contribution} + the strict
 * {@code member_contribution_presence} matview check per L16; annual
 * lines use the renewal-chain-active check per L9. Freshness warning
 * bubbles up on the result when the matview is >24h stale.
 */
export interface PersistencyCohortRow {
  cohortMonth: string;
  insuranceLine: string;
  checkpointMonths: number;
  cohortSize: number;
  stillActive: number;
  retentionRate: string;
}

export interface PersistencyCohortResult {
  rows: PersistencyCohortRow[];
  freshnessWarning: string | null;
}

export interface PersistencyCohortParams {
  periodStart: string;
  periodEnd: string;
  checkpoints?: string;        // csv, e.g. "6,12,24"
  insuranceLine?: string | null;
  reportingCurrency?: string;
}

function persistencyParams(opts: PersistencyCohortParams): Record<string, string> {
  const p: Record<string, string> = {
    periodStart: opts.periodStart,
    periodEnd:   opts.periodEnd,
  };
  if (opts.checkpoints)       p['checkpoints']       = opts.checkpoints;
  if (opts.insuranceLine)     p['insuranceLine']     = opts.insuranceLine;
  if (opts.reportingCurrency) p['reportingCurrency'] = opts.reportingCurrency;
  return p;
}

@Injectable({ providedIn: 'root' })
export class PersistencyCohortReportService {
  constructor(private api: ApiService) {}

  get(opts: PersistencyCohortParams): Observable<ReportResponse<PersistencyCohortResult>> {
    return this.api.get<ReportResponse<PersistencyCohortResult>>(
      '/reports/policy-lifecycle/persistency-cohort', persistencyParams(opts));
  }

  exportExcel(opts: PersistencyCohortParams): Observable<Blob> {
    return this.api.getBlob(
      '/reports/policy-lifecycle/persistency-cohort/export', persistencyParams(opts));
  }
}
